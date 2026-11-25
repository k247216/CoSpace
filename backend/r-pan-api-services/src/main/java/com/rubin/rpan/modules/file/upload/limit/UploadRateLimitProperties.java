package com.rubin.rpan.modules.file.upload.limit;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "rpan.upload.init-limit")
public class UploadRateLimitProperties {
  private boolean enabled = true;

  @Min(1)
  @Max(1000)
  private int capacity = 10;

  @Min(100)
  @Max(3600000)
  private long refillIntervalMs = 1000;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean value) {
    enabled = value;
  }

  public int getCapacity() {
    return capacity;
  }

  public void setCapacity(int value) {
    capacity = value;
  }

  public long getRefillIntervalMs() {
    return refillIntervalMs;
  }

  public void setRefillIntervalMs(long value) {
    refillIntervalMs = value;
  }
}
