package com.rubin.rpan.modules.file.cleanup.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "rpan.cleanup")
public class CleanupProperties {
  private boolean enabled = true;
  @NotBlank private String nameserver = "127.0.0.1:9876";
  @NotBlank private String topic = "rpan-file-cleanup";
  @NotBlank private String producerGroup = "rpan-cleanup-producer";
  @NotBlank private String consumerGroup = "rpan-cleanup-consumer";

  @Min(1)
  private int batchSize = 20;

  @Min(1)
  private int maxAttempts = 5;

  @Min(1000)
  private long leaseMs = 30000;

  @Min(1000)
  private long retryDelayMs = 10000;

  @Min(1000)
  private long redeliveryMs = 60000;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    enabled = v;
  }

  public String getNameserver() {
    return nameserver;
  }

  public void setNameserver(String v) {
    nameserver = v;
  }

  public String getTopic() {
    return topic;
  }

  public void setTopic(String v) {
    topic = v;
  }

  public String getProducerGroup() {
    return producerGroup;
  }

  public void setProducerGroup(String v) {
    producerGroup = v;
  }

  public String getConsumerGroup() {
    return consumerGroup;
  }

  public void setConsumerGroup(String v) {
    consumerGroup = v;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int v) {
    batchSize = v;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int v) {
    maxAttempts = v;
  }

  public long getLeaseMs() {
    return leaseMs;
  }

  public void setLeaseMs(long v) {
    leaseMs = v;
  }

  public long getRetryDelayMs() {
    return retryDelayMs;
  }

  public void setRetryDelayMs(long v) {
    retryDelayMs = v;
  }

  public long getRedeliveryMs() {
    return redeliveryMs;
  }

  public void setRedeliveryMs(long v) {
    redeliveryMs = v;
  }
}
