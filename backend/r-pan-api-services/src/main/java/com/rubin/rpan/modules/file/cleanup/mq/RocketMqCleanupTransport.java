package com.rubin.rpan.modules.file.cleanup.mq;

import com.rubin.rpan.modules.file.cleanup.config.CleanupProperties;
import com.rubin.rpan.modules.file.cleanup.service.CleanupTaskExecutor;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "rpan.cleanup",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RocketMqCleanupTransport implements CleanupMessageSender, DisposableBean {
  private static final Logger log = LoggerFactory.getLogger(RocketMqCleanupTransport.class);
  private final CleanupProperties properties;
  private final CleanupTaskExecutor executor;
  private DefaultMQProducer producer;
  private DefaultMQPushConsumer consumer;
  private boolean closed;

  public RocketMqCleanupTransport(CleanupProperties properties, CleanupTaskExecutor executor) {
    this.properties = properties;
    this.executor = executor;
  }

  @Override
  public void send(long taskId) throws Exception {
    DefaultMQProducer active = ensureStarted();
    Message message =
        new Message(
            properties.getTopic(),
            "CLEANUP_V1",
            String.valueOf(taskId),
            ("1:" + taskId).getBytes(StandardCharsets.UTF_8));
    SendResult result = active.send(message, 3000);
    if (result.getSendStatus() != SendStatus.SEND_OK)
      throw new IllegalStateException("Broker did not confirm durable send");
  }

  public synchronized DefaultMQProducer ensureStarted() throws Exception {
    if (closed) throw new IllegalStateException("Cleanup transport is closed");
    // 核心逻辑，如果启动了，就直接复用
    if (producer != null) return producer;
    // 没启动就去创建
    String instance = "rpan-cleanup-" + UUID.randomUUID();
    DefaultMQProducer p = new DefaultMQProducer(properties.getProducerGroup());
    DefaultMQPushConsumer c = new DefaultMQPushConsumer(properties.getConsumerGroup());

    p.setNamesrvAddr(properties.getNameserver());
    c.setNamesrvAddr(properties.getNameserver());
    p.setInstanceName(instance);
    c.setInstanceName(instance);
    p.setVipChannelEnabled(false);
    c.setVipChannelEnabled(false);
    // 生产者发送失败，不在 SDK 内立即重试
    p.setRetryTimesWhenSendFailed(0); // Outbox拥有后续补发，避免扫描被长时间阻塞。
    // 已有消费位点：接着上次的位置消费。
    // 没有消费位点：从队列最早可用的位置开始。
    c.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
    // 消费线程池的核心线程数与最大线程数
    c.setConsumeMessageBatchMaxSize(1);
    c.setConsumeThreadMin(1);
    c.setConsumeThreadMax(2);
    // MQ 层最多重试消费 8 次
    c.setMaxReconsumeTimes(8);

    c.subscribe(properties.getTopic(), "CLEANUP_V1");

    c.registerMessageListener(
        (MessageListenerConcurrently)
            (messages, context) -> {
              for (org.apache.rocketmq.common.message.MessageExt message : messages) {
                final long id;
                try {
                  id = decodeTaskId(message.getBody());
                } catch (IllegalArgumentException invalid) {
                  log.warn("Ignoring malformed cleanup message, msgId={}", message.getMsgId());
                  continue;
                }
                try {
                  if (!executor.execute(id)) return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                } catch (Exception failure) {
                  log.warn(
                      "Cleanup message processing failed, msgId={}", message.getMsgId(), failure);
                  return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
              }
              return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });

    try {
      p.start();
      c.start();
      producer = p;
      consumer = c;
      return p;
    } catch (Exception e) {
      c.shutdown();
      p.shutdown();
      throw e;
    }
  }

  public static long decodeTaskId(byte[] body) {
    if (body == null || body.length > 32)
      throw new IllegalArgumentException("Invalid cleanup event");
    String value = new String(body, StandardCharsets.UTF_8);
    if (!value.matches("1:[1-9][0-9]{0,18}"))
      throw new IllegalArgumentException("Invalid cleanup event");
    return Long.parseLong(value.substring(2));
  }

  @Override
  public synchronized void destroy() {
    closed = true;
    if (consumer != null) consumer.shutdown();
    if (producer != null) producer.shutdown();
    consumer = null;
    producer = null;
  }
}
