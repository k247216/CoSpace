package com.rubin.rpan.modules.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.util.Objects;

/** 用户搜索历史返回实体 */
@Schema(description = "用户搜索历史返回实体")
public class RPanUserSearchHistoryVO implements Serializable {

  private static final long serialVersionUID = 3989329822023576785L;

  /** 搜索历史文案 */
  @Schema(description = "搜索历史文案")
  private String value;

  public RPanUserSearchHistoryVO() {}

  public String getValue() {
    return value;
  }

  public void setValue(String value) {
    this.value = value;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    RPanUserSearchHistoryVO that = (RPanUserSearchHistoryVO) o;
    return Objects.equals(value, that.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(value);
  }

  @Override
  public String toString() {
    return "RPanUserSearchHistoryVO{" + "value='" + value + '\'' + '}';
  }
}
