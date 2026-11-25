package com.rubin.rpan.modules.share.po;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.Objects;

/** 校验分享码参数实体 */
@Schema(description = "校验分享码参数实体")
public class CheckShareCodePO implements Serializable {

  private static final long serialVersionUID = 1573366453472435825L;

  /** 分享id */
  @Schema(description = "分享id", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotNull(message = "分享id不能为空")
  private Long shareId;

  /** 分享码 */
  @Schema(description = "分享码", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotBlank(message = "分享码不能为空")
  private String shareCode;

  public CheckShareCodePO() {}

  public Long getShareId() {
    return shareId;
  }

  public void setShareId(Long shareId) {
    this.shareId = shareId;
  }

  public String getShareCode() {
    return shareCode;
  }

  public void setShareCode(String shareCode) {
    this.shareCode = shareCode;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    CheckShareCodePO that = (CheckShareCodePO) o;
    return Objects.equals(shareId, that.shareId) && Objects.equals(shareCode, that.shareCode);
  }

  @Override
  public int hashCode() {
    return Objects.hash(shareId, shareCode);
  }

  @Override
  public String toString() {
    return "CheckShareCodePO{" + "shareId=" + shareId + ", shareCode='" + shareCode + '\'' + '}';
  }
}
