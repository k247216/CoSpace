package com.rubin.rpan.modules.file.quota.model;

public class UploadReservation {
  private String uploadId;
  private Long userId;
  private String requestId;
  private long bytes;
  private String state;

  public String getUploadId() {
    return uploadId;
  }

  public void setUploadId(String value) {
    uploadId = value;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long value) {
    userId = value;
  }

  public String getRequestId() {
    return requestId;
  }

  public void setRequestId(String value) {
    requestId = value;
  }

  public long getBytes() {
    return bytes;
  }

  public void setBytes(long value) {
    bytes = value;
  }

  public String getState() {
    return state;
  }

  public void setState(String value) {
    state = value;
  }
}
