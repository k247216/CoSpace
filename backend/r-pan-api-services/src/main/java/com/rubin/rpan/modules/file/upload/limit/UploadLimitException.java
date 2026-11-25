package com.rubin.rpan.modules.file.upload.limit;

public class UploadLimitException extends RuntimeException {
  private final boolean unavailable;
  private final long retryAfterSeconds;

  public UploadLimitException(boolean unavailable, long retryAfterSeconds, Throwable cause) {
    super(unavailable ? "上传限流服务暂不可用，请稍后重试" : "创建上传任务过于频繁，请稍后重试", cause);
    this.unavailable = unavailable;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public boolean isUnavailable() {
    return unavailable;
  }

  public long getRetryAfterSeconds() {
    return retryAfterSeconds;
  }
}
