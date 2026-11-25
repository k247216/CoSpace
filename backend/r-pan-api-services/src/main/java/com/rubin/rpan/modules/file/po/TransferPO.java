package com.rubin.rpan.modules.file.po;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.Objects;

/** 转移文件PO */
@Schema(description = "转移文件PO")
public class TransferPO implements Serializable {

  private static final long serialVersionUID = 5547494462940457810L;

  /** 要转移的文件ID，多个用__,__隔开 */
  @Schema(description = "转移的文件ID，多个用__,__隔开", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotBlank(message = "请选择要转移的文件")
  private String fileIds;

  /** 当前目录的父级目录ID */
  @Schema(description = "当前目录的父级目录ID", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotNull(message = "父id不能为空")
  private Long parentId;

  /** 要转移到的文件夹ID */
  @Schema(description = "要转移到的文件夹ID", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotNull(message = "请选择要转移到的文件夹")
  private Long targetParentId;

  public TransferPO() {}

  public String getFileIds() {
    return fileIds;
  }

  public void setFileIds(String fileIds) {
    this.fileIds = fileIds;
  }

  public Long getParentId() {
    return parentId;
  }

  public void setParentId(Long parentId) {
    this.parentId = parentId;
  }

  public Long getTargetParentId() {
    return targetParentId;
  }

  public void setTargetParentId(Long targetParentId) {
    this.targetParentId = targetParentId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    TransferPO that = (TransferPO) o;
    return Objects.equals(fileIds, that.fileIds)
        && Objects.equals(parentId, that.parentId)
        && Objects.equals(targetParentId, that.targetParentId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(fileIds, parentId, targetParentId);
  }

  @Override
  public String toString() {
    return "TransferPO{"
        + "fileIds='"
        + fileIds
        + '\''
        + ", parentId="
        + parentId
        + ", targetParentId="
        + targetParentId
        + '}';
  }
}
