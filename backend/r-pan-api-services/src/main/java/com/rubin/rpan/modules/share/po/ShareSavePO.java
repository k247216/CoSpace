package com.rubin.rpan.modules.share.po;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.Objects;

/** 创建分享链接PO */
@Schema(description = "保存到我的文件夹PO")
public class ShareSavePO implements Serializable {

  private static final long serialVersionUID = 1805035523468416712L;

  /** 要保存的文件id，多个用逗号隔开 */
  @Schema(description = "要保存的文件id，多个用逗号隔开", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotBlank(message = "请选择要保存的文件")
  private String fileIds;

  /** 要保存到的文件夹id */
  @Schema(description = "要保存到的文件夹id", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotNull(message = "请选择要保存到的文件夹")
  private Long targetParentId;

  public ShareSavePO() {}

  public String getFileIds() {
    return fileIds;
  }

  public void setFileIds(String fileIds) {
    this.fileIds = fileIds;
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
    ShareSavePO that = (ShareSavePO) o;
    return Objects.equals(fileIds, that.fileIds)
        && Objects.equals(targetParentId, that.targetParentId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(fileIds, targetParentId);
  }

  @Override
  public String toString() {
    return "ShareSavePO{"
        + "fileIds='"
        + fileIds
        + '\''
        + ", targetParentId="
        + targetParentId
        + '}';
  }
}
