package com.rubin.rpan.modules.file.upload.model;

import jakarta.validation.constraints.*;

public class UploadTaskInitRequest {
  private boolean directUpload;

  public boolean isDirectUpload() {
    return directUpload;
  }

  public void setDirectUpload(boolean directUpload) {
    this.directUpload = directUpload;
  }

  @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
  private String requestId;

  public String getRequestId() {
    return requestId;
  }

  public void setRequestId(String value) {
    requestId = value;
  }

  @NotBlank
  @Size(max = 255)
  private String name;

  @NotNull
  @Min(0)
  private Long size;

  @NotBlank
  @Pattern(regexp = "[0-9a-fA-F]{32}")
  private String md5;

  @NotNull private Long parentId;

  public String getName() {
    return name;
  }

  public void setName(String v) {
    name = v;
  }

  public Long getSize() {
    return size;
  }

  public void setSize(Long v) {
    size = v;
  }

  public String getMd5() {
    return md5;
  }

  public void setMd5(String v) {
    md5 = v;
  }

  public Long getParentId() {
    return parentId;
  }

  public void setParentId(Long v) {
    parentId = v;
  }
}
