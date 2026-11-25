package com.rubin.rpan.modules.file.quota.model;

public class UserSpaceAccount {
  private Long userId;
  private long totalBytes;
  private long usedBytes;
  private long reservedBytes;

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long value) {
    userId = value;
  }

  public long getTotalBytes() {
    return totalBytes;
  }

  public void setTotalBytes(long value) {
    totalBytes = value;
  }

  public long getUsedBytes() {
    return usedBytes;
  }

  public void setUsedBytes(long value) {
    usedBytes = value;
  }

  public long getReservedBytes() {
    return reservedBytes;
  }

  public void setReservedBytes(long value) {
    reservedBytes = value;
  }

  public long getAvailableBytes() {
    return totalBytes - usedBytes - reservedBytes;
  }
}
