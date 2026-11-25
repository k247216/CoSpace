package com.rubin.rpan.modules.file.upload.model;

/** 只确认这一片完整接收，不代表整文件已入库或已完成。 */
public class ChunkReceipt {
  private String uploadId;
  private Integer chunkIndex;
  private Long size;
  private String sha256;
  private Boolean duplicate;

  public String getUploadId() {
    return uploadId;
  }

  public void setUploadId(String v) {
    uploadId = v;
  }

  public Integer getChunkIndex() {
    return chunkIndex;
  }

  public void setChunkIndex(Integer v) {
    chunkIndex = v;
  }

  public Long getSize() {
    return size;
  }

  public void setSize(Long v) {
    size = v;
  }

  public String getSha256() {
    return sha256;
  }

  public void setSha256(String v) {
    sha256 = v;
  }

  public Boolean getDuplicate() {
    return duplicate;
  }

  public void setDuplicate(Boolean v) {
    duplicate = v;
  }
}
