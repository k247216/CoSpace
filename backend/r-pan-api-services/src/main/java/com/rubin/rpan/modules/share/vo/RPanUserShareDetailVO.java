package com.rubin.rpan.modules.share.vo;

import com.rubin.rpan.modules.file.vo.RPanUserFileVO;
import com.rubin.rpan.modules.share.entity.RPanShare;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/** 分享链接详情返回实体 */
@Schema(description = "分享链接详情返回实体")
public class RPanUserShareDetailVO implements Serializable {

  private static final long serialVersionUID = 5467063905263311932L;

  public RPanUserShareDetailVO() {}

  public RPanUserShareDetailVO(RPanShare rPanShare) {
    this.shareId = rPanShare.getShareId();
    this.shareName = rPanShare.getShareName();
    this.createTime = rPanShare.getCreateTime();
    this.shareDay = rPanShare.getShareDay();
    this.shareEndTime = rPanShare.getShareEndTime();
  }

  /** 分享id */
  @JsonSerialize(using = ToStringSerializer.class)
  @Schema(description = "分享id")
  private Long shareId;

  /** 分享名称 */
  @Schema(description = "分享名称")
  private String shareName;

  /** 创建时间 */
  @Schema(description = "创建时间")
  private Date createTime;

  /** 分享有效天数（永久有效为0） */
  @Schema(description = "分享有效天数（永久有效为0）")
  private Integer shareDay;

  /** 分享结束时间 */
  @Schema(description = "分享结束时间")
  private Date shareEndTime;

  /** 分享的文件列表 */
  @Schema(description = "分享的文件列表")
  private List<RPanUserFileVO> rPanUserFileVOList;

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

  public Date getCreateTime() {
    return createTime;
  }

  public void setCreateTime(Date createTime) {
    this.createTime = createTime;
  }

  public Integer getShareDay() {
    return shareDay;
  }

  public void setShareDay(Integer shareDay) {
    this.shareDay = shareDay;
  }

  public Date getShareEndTime() {
    return shareEndTime;
  }

  public void setShareEndTime(Date shareEndTime) {
    this.shareEndTime = shareEndTime;
  }

  public List<RPanUserFileVO> getrPanUserFileVOList() {
    return rPanUserFileVOList;
  }

  public void setrPanUserFileVOList(List<RPanUserFileVO> rPanUserFileVOList) {
    this.rPanUserFileVOList = rPanUserFileVOList;
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
    RPanUserShareDetailVO that = (RPanUserShareDetailVO) o;
    return Objects.equals(shareId, that.shareId)
        && Objects.equals(shareName, that.shareName)
        && Objects.equals(createTime, that.createTime)
        && Objects.equals(shareDay, that.shareDay)
        && Objects.equals(shareEndTime, that.shareEndTime)
        && Objects.equals(rPanUserFileVOList, that.rPanUserFileVOList)
        && Objects.equals(shareUserInfoVO, that.shareUserInfoVO);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        shareId,
        shareName,
        createTime,
        shareDay,
        shareEndTime,
        rPanUserFileVOList,
        shareUserInfoVO);
  }

  @Override
  public String toString() {
    return "RPanUserShareDetailVO{"
        + "shareId="
        + shareId
        + ", shareName='"
        + shareName
        + '\''
        + ", createTime="
        + createTime
        + ", shareDay="
        + shareDay
        + ", shareEndTime="
        + shareEndTime
        + ", rPanUserFileVOList="
        + rPanUserFileVOList
        + ", shareUserInfoVO="
        + shareUserInfoVO
        + '}';
  }
}
