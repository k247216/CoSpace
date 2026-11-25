package com.rubin.rpan.modules.file.cleanup.mq;

import com.rubin.rpan.modules.file.cleanup.config.CleanupProperties;
import com.rubin.rpan.modules.file.dao.RPanFileCleanupTaskMapper;
import com.rubin.rpan.modules.file.entity.RPanFileCleanupTask;
import java.time.Clock;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "rpan.cleanup",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CleanupTaskPublisher {
  private static final Logger log = LoggerFactory.getLogger(CleanupTaskPublisher.class);
  private final RPanFileCleanupTaskMapper tasks;
  private final CleanupMessageSender sender;
  private final CleanupProperties properties;
  private Clock clock = Clock.systemUTC();

  public CleanupTaskPublisher(
      RPanFileCleanupTaskMapper tasks, CleanupMessageSender sender, CleanupProperties properties) {
    this.tasks = tasks;
    this.sender = sender;
    this.properties = properties;
  }

  public void setClock(Clock clock) {
    this.clock = clock;
  }

  @Scheduled(
      initialDelayString = "${rpan.cleanup.initial-delay-ms:5000}",
      fixedDelayString = "${rpan.cleanup.scan-delay-ms:5000}")
  public void publishDue() {
    Date now = new Date(clock.millis());
    try {
      // 用当前时间查询“到期、尚未完成”的任务，然后调用 publish()，把任务 ID 发给 MQ。
      for (RPanFileCleanupTask task : tasks.selectDue(now, properties.getBatchSize()))
        publish(task, now);
    } catch (Exception e) {
      log.warn("Cleanup task scan failed", e);
    }
  }

  private void publish(RPanFileCleanupTask task, Date now) {
    try {
      int previous = task.getDeliveryAttempts();
      Date next = new Date(now.getTime() + properties.getRedeliveryMs());
      // 通过条件更新防止两个投递者同时抢到同一次投递
      // 靠的是 SQL 条件更新的原子性，其中 delivery_attempts 被当作版本号使用，类似 CAS：
      // 只有数据库里的版本仍等于我读到的旧版本，才允许修改。
      if (tasks.reserveDelivery(task.getTaskId(), previous, now, next) != 1) return;
      // 发送成功：投递版本仍匹配，就补记 SENT。
      // 发送失败：投递版本匹配，而且消费者没有推进过任务，才记录失败。
      //
      // 这里更新 0 行不一定是异常，也可能说明结果已经过时，被这些条件正确拦住了
      try {
        sender.send(task.getTaskId());
        tasks.recordDelivery(
            task.getTaskId(),
            previous + 1,
            true,
            null,
            new Date(clock.millis()),
            task.getExecutionAttempts());
      } catch (Exception e) {
        // 不覆盖消费者已写入的执行终态；发送失败保留任务，下一轮到期后重发。
        // 执行状态仍是 PENDING/RETRY，覆盖正在处理、已成功或已转人工的任务信息
        // 执行次数仍等于发送前的次数，覆盖消费者执行过一轮后写入的新失败原因
        tasks.recordDelivery(
            task.getTaskId(),
            previous + 1,
            false,
            "SEND_FAILED: " + e.getClass().getSimpleName(),
            new Date(clock.millis()),
            task.getExecutionAttempts());
        log.warn("Cleanup message send failed, taskId={}", task.getTaskId(), e);
      }
    } catch (Exception e) {
      log.warn("Cleanup task delivery update failed, taskId={}", task.getTaskId(), e);
    }
  }
}
