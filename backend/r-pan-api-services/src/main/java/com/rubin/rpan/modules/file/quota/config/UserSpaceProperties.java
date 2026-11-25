package com.rubin.rpan.modules.file.quota.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "rpan.space")
public class UserSpaceProperties {
  @Min(1)
  @Max(1125899906842624L)
  private long defaultTotalBytes = 10737418240L;

  public long getDefaultTotalBytes() {
    return defaultTotalBytes;
  }

  public void setDefaultTotalBytes(long value) {
    defaultTotalBytes = value;
  }
}
