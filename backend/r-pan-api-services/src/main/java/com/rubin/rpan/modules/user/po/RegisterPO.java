package com.rubin.rpan.modules.user.po;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.validator.constraints.Length;

/** 用户注册PO */
@Schema(description = "用户注册PO")
public class RegisterPO implements Serializable {

  private static final long serialVersionUID = -6407043501200872229L;

  /** 用户名 */
  @Schema(description = "用户名", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotBlank(message = "用户名不能为空")
  @Pattern(regexp = "^[0-9A-Za-z]{6,16}$", message = "请输入6-16位只包含数字和字母的用户名")
  private String username;

  /** 密码 */
  @Schema(description = "密码", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotBlank(message = "密码不能为空")
  @Length(min = 8, max = 16, message = "请输入8-16位的密码")
  private String password;

  /** 密保问题 */
  @Schema(description = "密保问题", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotBlank(message = "密保问题不能为空")
  @Length(max = 100, message = "密保问题不能超过100个字")
  private String question;

  /** 密保答案 */
  @Schema(description = "密保答案", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotBlank(message = "密保答案不能为空")
  @Length(max = 100, message = "密保答案不能超过100个字")
  private String answer;

  public RegisterPO() {}

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getQuestion() {
    return question;
  }

  public void setQuestion(String question) {
    this.question = question;
  }

  public String getAnswer() {
    return answer;
  }

  public void setAnswer(String answer) {
    this.answer = answer;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    RegisterPO that = (RegisterPO) o;
    return Objects.equals(username, that.username)
        && Objects.equals(password, that.password)
        && Objects.equals(question, that.question)
        && Objects.equals(answer, that.answer);
  }

  @Override
  public int hashCode() {
    return Objects.hash(username, password, question, answer);
  }

  @Override
  public String toString() {
    return "RegisterPO{"
        + "username='"
        + username
        + '\''
        + ", password='"
        + password
        + '\''
        + ", question='"
        + question
        + '\''
        + ", answer='"
        + answer
        + '\''
        + '}';
  }
}
