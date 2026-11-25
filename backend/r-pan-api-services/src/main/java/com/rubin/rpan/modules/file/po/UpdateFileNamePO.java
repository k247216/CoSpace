package com.rubin.rpan.modules.file.po;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.Objects;

/** 更新文件名PO */
@Schema(description = "更新文件名PO")
public class UpdateFileNamePO implements Serializable {

  private static final long serialVersionUID = 169947393401301942L;

  /** 文件记录ID */
  @Schema(description = "文件记录ID", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotNull(message = "文件id不能为空")
  private Long fileId;

  /** 新文件名 */
  @Schema(description = "新文件名", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotBlank(message = "新文件名称不能为空")
  private String newFilename;

  public UpdateFileNamePO() {}

  public Long getFileId() {
    return fileId;
  }

  public void setFileId(Long fileId) {
    this.fileId = fileId;
  }

  public String getNewFilename() {
    return newFilename;
  }

  public void setNewFilename(String newFilename) {
    this.newFilename = newFilename;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    UpdateFileNamePO that = (UpdateFileNamePO) o;
    return Objects.equals(fileId, that.fileId) && Objects.equals(newFilename, that.newFilename);
  }

  @Override
  public int hashCode() {
    return Objects.hash(fileId, newFilename);
  }

  @Override
  public String toString() {
    return "UpdateFileNamePO{" + "fileId=" + fileId + ", newFilename='" + newFilename + '\'' + '}';
  }
}
