package com.rubin.rpan.modules.file.po;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.io.Serializable;
import java.util.Objects;

@Schema(description = "文件秒传PO")
public class FileSecUploadPO implements Serializable {

  private static final long serialVersionUID = 1883102378277804464L;

  @Schema(description = "文件名称", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotBlank(message = "文件名称不能为空")
  private String name;

  @Schema(description = "文件对应的md5", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotBlank(message = "md5不能为空")
  @Pattern(regexp = "[0-9a-fA-F]{32}", message = "md5格式不正确")
  private String md5;

  @Schema(description = "文件字节数", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotNull(message = "文件大小不能为空")
  @Min(value = 0, message = "文件大小不能为负数")
  private Long size;

  public Long getSize() {
    return size;
  }

  public void setSize(Long size) {
    this.size = size;
  }

  @Schema(description = "父id", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotNull(message = "父id不能为空")
  private Long parentId;

  public FileSecUploadPO() {}

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getMd5() {
    return md5;
  }

  public void setMd5(String md5) {
    this.md5 = md5;
  }

  public Long getParentId() {
    return parentId;
  }

  public void setParentId(Long parentId) {
    this.parentId = parentId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    FileSecUploadPO that = (FileSecUploadPO) o;
    return Objects.equals(name, that.name)
        && Objects.equals(md5, that.md5)
        && Objects.equals(size, that.size)
        && Objects.equals(parentId, that.parentId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, md5, size, parentId);
  }

  @Override
  public String toString() {
    return "FileSecUploadPO{"
        + "name='"
        + name
        + '\''
        + ", md5='"
        + md5
        + '\''
        + ", size="
        + size
        + ", parentId="
        + parentId
        + '}';
  }
}
