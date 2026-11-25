package com.rubin.rpan.modules.file.entity;

import com.rubin.rpan.modules.file.upload.model.UploadTaskState;
import java.util.Date;

public class RPanUploadTask {
  /** 服务端生成的上传任务ID，用于隔离不同上传任务，不使用文件MD5代替 */
  private String uploadId;

  private Long workspaceId;
  private String collectionId;
  private Long collectionMemberGeneration;

  public Long getCollectionMemberGeneration() {
    return collectionMemberGeneration;
  }

  public void setCollectionMemberGeneration(Long value) {
    collectionMemberGeneration = value;
  }

  public String getCollectionId() {
    return collectionId;
  }

  public void setCollectionId(String value) {
    collectionId = value;
  }

  private Long membershipGeneration;

  public Long getWorkspaceId() {
    return workspaceId;
  }

  public void setWorkspaceId(Long value) {
    workspaceId = value;
  }

  public Long getMembershipGeneration() {
    return membershipGeneration;
  }

  public void setMembershipGeneration(Long value) {
    membershipGeneration = value;
  }

  private boolean directUpload;
  private String multipartId;

  public boolean isDirectUpload() {
    return directUpload;
  }

  public void setDirectUpload(boolean directUpload) {
    this.directUpload = directUpload;
  }

  public String getMultipartId() {
    return multipartId;
  }

  public void setMultipartId(String multipartId) {
    this.multipartId = multipartId;
  }

  private Date artifactsCleanedAt;

  private String artifactCleanupError;

  public Date getArtifactsCleanedAt() {
    return artifactsCleanedAt;
  }

  public void setArtifactsCleanedAt(Date v) {
    artifactsCleanedAt = v;
  }

  public String getArtifactCleanupError() {
    return artifactCleanupError;
  }

  public void setArtifactCleanupError(String v) {
    artifactCleanupError = v;
  }

  /** 上传任务所属用户ID，用于校验分片上传和进度查询的访问权限 */
  private Long userId;

  /** 上传完成后用户文件所在的逻辑目录ID，不是磁盘目录路径 */
  private Long parentId;

  /** 用户提交的完整文件名称，用于上传完成后的显示，不直接作为分片存储路径 */
  private String filename;

  /** 客户端声明的整文件MD5，供完成阶段校验内容使用，不作为访问授权依据 */
  private String expectedMd5;

  /** 整个文件的预期大小，单位为字节，不是当前分片大小 */
  private Long fileSize;

  /** 初始化任务时固定的标准分片大小，单位为字节，最后一片可以小于此值 */
  private Integer chunkSize;

  /** 预期分片总数，用于校验分片编号范围和判断是否收齐分片 */
  private Integer totalChunks;

  /** 创建任务时选定的存储实现标识，用于检查后续请求的存储配置是否一致 */
  private String storageType;

  /** 上传任务状态：UPLOADING接收中、FINALIZING收尾中、COMPLETED完成、FAILED失败、EXPIRED过期 */
  private UploadTaskState taskState;

  /** 上传任务的过期时间，不等同于Redis进度缓存的过期时间 */
  private Date expiresAt;

  /** 完成阶段使用的正式存储路径，预留用于追踪文件移动和入库之间的恢复过程 */
  private String finalPath;

  /** 完成后关联的物理文件记录ID，对应r_pan_file.file_id，尚未入库时为空 */
  private Long realFileId;

  /** 完成后关联的用户文件记录ID，对应r_pan_user_file.file_id，区别于物理文件ID */
  private Long userFileId;

  /** 最近一次任务失败原因，预留用于错误追踪，不代表所有请求异常都会写入此字段 */
  private String lastError;

  /** 上传任务创建时间 */
  private Date createdAt;

  /** 上传任务记录最近一次更新时间 */
  private Date updatedAt;

  public String getUploadId() {
    return uploadId;
  }

  public void setUploadId(String v) {
    uploadId = v;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long v) {
    userId = v;
  }

  public Long getParentId() {
    return parentId;
  }

  public void setParentId(Long v) {
    parentId = v;
  }

  public String getFilename() {
    return filename;
  }

  public void setFilename(String v) {
    filename = v;
  }

  public String getExpectedMd5() {
    return expectedMd5;
  }

  public void setExpectedMd5(String v) {
    expectedMd5 = v;
  }

  public Long getFileSize() {
    return fileSize;
  }

  public void setFileSize(Long v) {
    fileSize = v;
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

  public String getStorageType() {
    return storageType;
  }

  public void setStorageType(String v) {
    storageType = v;
  }

  public UploadTaskState getTaskState() {
    return taskState;
  }

  public void setTaskState(UploadTaskState v) {
    taskState = v;
  }

  public Date getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Date v) {
    expiresAt = v;
  }

  public String getFinalPath() {
    return finalPath;
  }

  public void setFinalPath(String v) {
    finalPath = v;
  }

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

  public Date getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Date v) {
    createdAt = v;
  }

  public Date getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Date v) {
    updatedAt = v;
  }
}
