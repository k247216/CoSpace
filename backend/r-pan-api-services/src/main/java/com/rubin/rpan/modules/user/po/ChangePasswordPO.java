package com.rubin.rpan.modules.user.po;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.validator.constraints.Length;

/** 修改密码PO */
@Schema(description = "修改密码PO")
public class ChangePasswordPO implements Serializable {

  private static final long serialVersionUID = -7503691157142209504L;

  /** 旧密码 */
  @Schema(description = "旧密码", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotBlank(message = "原密码不能为空")
  @Length(min = 8, max = 16, message = "请输入8-16位的原密码")
  private String password;

  /** 新密码 */
  @Schema(description = "新密码", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotBlank(message = "新密码不能为空")
  @Length(min = 8, max = 16, message = "请输入8-16位的新密码")
  private String newPassword;

  public ChangePasswordPO() {}

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getNewPassword() {
    return newPassword;
  }

  public void setNewPassword(String newPassword) {
    this.newPassword = newPassword;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ChangePasswordPO that = (ChangePasswordPO) o;
    return Objects.equals(password, that.password) && Objects.equals(newPassword, that.newPassword);
  }

  @Override
  public int hashCode() {
    return Objects.hash(password, newPassword);
  }

  @Override
  public String toString() {
    return "ChangePasswordPO{"
        + "password='"
        + password
        + '\''
        + ", newPassword='"
        + newPassword
        + '\''
        + '}';
  }
}
