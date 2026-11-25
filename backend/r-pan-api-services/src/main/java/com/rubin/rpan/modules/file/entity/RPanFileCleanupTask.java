package com.rubin.rpan.modules.file.entity;

import com.rubin.rpan.modules.file.constant.CleanupDeliveryState;
import com.rubin.rpan.modules.file.constant.CleanupExecutionState;
import java.io.Serializable;
import java.util.Date;

public class RPanFileCleanupTask implements Serializable {
  /** Java序列化版本号 */
  private static final long serialVersionUID = 1L;

  /** 清理任务ID，同时作为RocketMQ消息的业务Key */
  private Long taskId;

  /** 待清理的物理文件记录ID，对应r_pan_file.file_id，不是用户文件记录ID */
  private Long realFileId;

  /** 创建任务时保存的存储实现标识，用于防止交给不匹配的存储实现执行删除 */
  private String storageType;

  /** 待清理内容的存储定位快照，本地存储时为文件路径，创建任务后不随意变更 */
  private String storageLocator;

  /** 清理执行状态：PENDING待处理、PROCESSING处理中、RETRY重试、SUCCEEDED成功、MANUAL人工处理 */
  private CleanupExecutionState executionState;

  /** 消息投递状态：PENDING待投递、SENT已发送；已发送不代表清理成功 */
  private CleanupDeliveryState deliveryState;

  /** 已预留的消息投递尝试次数，不等于Broker实际保存的消息数量 */
  private Integer deliveryAttempts;

  /** 已领取的清理执行尝试次数，领取后进程退出也会计入，用于限制自动重试 */
  private Integer executionAttempts;

  /** 下一次允许扫描投递的时间，也用于长期未完成任务的补发调度 */
  private Date nextDeliveryAt;

  /** 本次执行的唯一令牌，不是用户ID或固定机器名，用于阻止旧执行者覆盖新结果 */
  private String leaseOwner;

  /** 当前执行租约的截止时间，到期后允许其他执行者重新领取任务 */
  private Date leaseUntil;

  /** 最近一次投递或清理失败的原因，保存时应限制长度并避免敏感信息 */
  private String lastError;

  /** 清理任务创建时间 */
  private Date createdAt;

  /** 清理任务记录最近一次更新时间 */
  private Date updatedAt;

  /** 清理成功的时间，尚未成功时为空，不表示消息发送时间 */
  private Date completedAt;

  public Long getTaskId() {
    return taskId;
  }

  public void setTaskId(Long taskId) {
    this.taskId = taskId;
  }

  public Long getRealFileId() {
    return realFileId;
  }

  public void setRealFileId(Long realFileId) {
    this.realFileId = realFileId;
  }

  public String getStorageType() {
    return storageType;
  }

  public void setStorageType(String storageType) {
    this.storageType = storageType;
  }

  public String getStorageLocator() {
    return storageLocator;
  }

  public void setStorageLocator(String storageLocator) {
    this.storageLocator = storageLocator;
  }

  public CleanupExecutionState getExecutionState() {
    return executionState;
  }

  public void setExecutionState(CleanupExecutionState executionState) {
    this.executionState = executionState;
  }

  public CleanupDeliveryState getDeliveryState() {
    return deliveryState;
  }

  public void setDeliveryState(CleanupDeliveryState deliveryState) {
    this.deliveryState = deliveryState;
  }

  public Integer getDeliveryAttempts() {
    return deliveryAttempts;
  }

  public void setDeliveryAttempts(Integer deliveryAttempts) {
    this.deliveryAttempts = deliveryAttempts;
  }

  public Integer getExecutionAttempts() {
    return executionAttempts;
  }

  public void setExecutionAttempts(Integer executionAttempts) {
    this.executionAttempts = executionAttempts;
  }

  public Date getNextDeliveryAt() {
    return nextDeliveryAt;
  }

  public void setNextDeliveryAt(Date nextDeliveryAt) {
    this.nextDeliveryAt = nextDeliveryAt;
  }

  public String getLeaseOwner() {
    return leaseOwner;
  }

  public void setLeaseOwner(String leaseOwner) {
    this.leaseOwner = leaseOwner;
  }

  public Date getLeaseUntil() {
    return leaseUntil;
  }

  public void setLeaseUntil(Date leaseUntil) {
    this.leaseUntil = leaseUntil;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String lastError) {
    this.lastError = lastError;
  }

  public Date getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Date createdAt) {
    this.createdAt = createdAt;
  }

  public Date getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Date updatedAt) {
    this.updatedAt = updatedAt;
  }

  public Date getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(Date completedAt) {
    this.completedAt = completedAt;
  }
}
