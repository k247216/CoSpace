package com.rubin.rpan.modules.file.po;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.Objects;

/** 删除文件PO */
@Schema(description = "删除文件PO")
public class DeletePO implements Serializable {

  private static final long serialVersionUID = -1604961110716421918L;

  /** 父级ID */
  @Schema(description = "父级ID", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotNull(message = "父id不能为空")
  private Long parentId;

  /** 要删除的文件记录ID，多个用__,__隔开 */
  @Schema(description = "要删除的文件记录ID，多个用__,__隔开", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotBlank(message = "文件id不能为空")
  private String fileIds;

  public DeletePO() {}

  public Long getParentId() {
    return parentId;
  }

  public void setParentId(Long parentId) {
    this.parentId = parentId;
  }

  public String getFileIds() {
    return fileIds;
  }

  public void setFileIds(String fileIds) {
    this.fileIds = fileIds;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    DeletePO deletePO = (DeletePO) o;
    return Objects.equals(parentId, deletePO.parentId) && Objects.equals(fileIds, deletePO.fileIds);
  }

  @Override
  public int hashCode() {
    return Objects.hash(parentId, fileIds);
  }

  @Override
  public String toString() {
    return "DeletePO{" + "parentId=" + parentId + ", fileIds='" + fileIds + '\'' + '}';
  }
}
