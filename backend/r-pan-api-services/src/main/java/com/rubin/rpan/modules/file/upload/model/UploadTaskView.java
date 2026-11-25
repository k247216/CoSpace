package com.rubin.rpan.modules.file.upload.model;

import java.util.Date;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

public class UploadTaskView {
  private boolean directUpload;

  public boolean isDirectUpload() {
    return directUpload;
  }

  public void setDirectUpload(boolean directUpload) {
    this.directUpload = directUpload;
  }

  @JsonSerialize(using = ToStringSerializer.class)
  private Long realFileId;

  @JsonSerialize(using = ToStringSerializer.class)
  private Long userFileId;

  private String lastError;

  public Long getRealFileId() {
    return realFileId;
  }

  public void setRealFileId(Long v) {
    realFileId = v;
  }

  public Long getUserFileId() {
    return userFileId;
  }

  public void setUserFileId(Long v) {
    userFileId = v;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String v) {
    lastError = v;
  }

  private String uploadId;
  private String name;
  private Long size;

  @JsonSerialize(using = ToStringSerializer.class)
  private Long parentId;

  private Integer chunkSize;
  private Integer totalChunks;
  private UploadTaskState state;
  private Date expiresAt;
  private List<Integer> uploadedChunks;
  private Boolean allChunksReceived;

  public String getUploadId() {
    return uploadId;
  }

  public void setUploadId(String v) {
    uploadId = v;
  }

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

  public Long getParentId() {
    return parentId;
  }

  public void setParentId(Long v) {
    parentId = v;
  }

  public Integer getChunkSize() {
    return chunkSize;
  }

  public void setChunkSize(Integer v) {
    chunkSize = v;
  }

  public Integer getTotalChunks() {
    return totalChunks;
  }

  public void setTotalChunks(Integer v) {
    totalChunks = v;
  }

  public UploadTaskState getState() {
    return state;
  }

  public void setState(UploadTaskState v) {
    state = v;
  }

  public Date getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Date v) {
    expiresAt = v;
  }

  public List<Integer> getUploadedChunks() {
    return uploadedChunks;
  }

  public void setUploadedChunks(List<Integer> v) {
    uploadedChunks = v;
  }

  public Boolean getAllChunksReceived() {
    return allChunksReceived;
  }

  public void setAllChunksReceived(Boolean v) {
    allChunksReceived = v;
  }
}
