package com.rubin.rpan.modules.file.po;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.Objects;

/** 创建文件夹PO */
@Schema(description = "创建文件夹PO")
public class CreateFolderPO implements Serializable {

  private static final long serialVersionUID = -4926298360074320330L;

  /** 父级ID */
  @Schema(description = "父级ID", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotNull(message = "父id不能为空")
  private Long parentId;

  /** 文件夹名称 */
  @Schema(description = "文件夹名称", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotBlank(message = "文件夹名称不能为空")
  private String folderName;

  public CreateFolderPO() {}

  public Long getParentId() {
    return parentId;
  }

  public void setParentId(Long parentId) {
    this.parentId = parentId;
  }

  public String getFolderName() {
    return folderName;
  }

  public void setFolderName(String folderName) {
    this.folderName = folderName;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    CreateFolderPO that = (CreateFolderPO) o;
    return Objects.equals(parentId, that.parentId) && Objects.equals(folderName, that.folderName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(parentId, folderName);
  }

  @Override
  public String toString() {
    return "CreateFolderPO{" + "parentId=" + parentId + ", folderName='" + folderName + '\'' + '}';
  }
}
