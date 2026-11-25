package com.rubin.rpan.modules.share.vo;

import com.rubin.rpan.modules.share.entity.RPanShare;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.util.Objects;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/** 分享链接简单详情返回实体 */
@Schema(description = "分享链接简单详情返回实体")
public class RPanUserShareSimpleDetailVO implements Serializable {

  private static final long serialVersionUID = 1601779429991293326L;

  public RPanUserShareSimpleDetailVO() {}

  public RPanUserShareSimpleDetailVO(RPanShare rPanShare) {
    this.shareId = rPanShare.getShareId();
    this.shareName = rPanShare.getShareName();
  }

  /** 分享id */
  @JsonSerialize(using = ToStringSerializer.class)
  @Schema(description = "分享id")
  private Long shareId;

  /** 分享名称 */
  @Schema(description = "分享名称")
  private String shareName;

  /** 分享者信息 */
  @Schema(description = "分享者信息")
  private ShareUserInfoVO shareUserInfoVO;

  public Long getShareId() {
    return shareId;
  }

  public void setShareId(Long shareId) {
    this.shareId = shareId;
  }

  public String getShareName() {
    return shareName;
  }

  public void setShareName(String shareName) {
    this.shareName = shareName;
  }

  public ShareUserInfoVO getShareUserInfoVO() {
    return shareUserInfoVO;
  }

  public void setShareUserInfoVO(ShareUserInfoVO shareUserInfoVO) {
    this.shareUserInfoVO = shareUserInfoVO;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    RPanUserShareSimpleDetailVO that = (RPanUserShareSimpleDetailVO) o;
    return Objects.equals(shareId, that.shareId)
        && Objects.equals(shareName, that.shareName)
        && Objects.equals(shareUserInfoVO, that.shareUserInfoVO);
  }

  @Override
  public int hashCode() {
    return Objects.hash(shareId, shareName, shareUserInfoVO);
  }

  @Override
  public String toString() {
    return "RPanUserShareSimpleDetailVO{"
        + "shareId="
        + shareId
        + ", shareName='"
        + shareName
        + '\''
        + ", shareUserInfoVO="
        + shareUserInfoVO
        + '}';
  }
}
