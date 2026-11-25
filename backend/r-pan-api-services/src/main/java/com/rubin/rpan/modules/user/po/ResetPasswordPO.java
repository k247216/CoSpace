package com.rubin.rpan.modules.user.po;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.validator.constraints.Length;

/** 用户重置密码PO */
@Schema(description = "用户重置密码PO")
public class ResetPasswordPO implements Serializable {

  private static final long serialVersionUID = 8786438472271278257L;

  /** 用户名 */
  @Schema(description = "用户名", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotBlank(message = "用户名不能为空")
  @Pattern(regexp = "^[0-9A-Za-z]{6,16}$", message = "请输入6-16位只包含数字和字母的用户名")
  private String username;

  /** 新密码 */
  @Schema(description = "新密码", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotBlank(message = "密码不能为空")
  @Length(min = 8, max = 16, message = "请输入8-16位的密码")
  private String newPassword;

  /** 忘记密码验证token */
  @Schema(description = "忘记密码验证token", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotBlank(message = "token不能为空")
  private String token;

  public ResetPasswordPO() {}

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getNewPassword() {
    return newPassword;
  }

  public void setNewPassword(String newPassword) {
    this.newPassword = newPassword;
  }

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ResetPasswordPO that = (ResetPasswordPO) o;
    return Objects.equals(username, that.username)
        && Objects.equals(newPassword, that.newPassword)
        && Objects.equals(token, that.token);
  }

  @Override
  public int hashCode() {
    return Objects.hash(username, newPassword, token);
  }

  @Override
  public String toString() {
    return "ResetPasswordPO{"
        + "username='"
        + username
        + '\''
        + ", newPassword='"
        + newPassword
        + '\''
        + ", token='"
        + token
        + '\''
        + '}';
  }
}
