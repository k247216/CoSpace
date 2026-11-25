package com.rubin.rpan.util;

/** 密码工具类 */
public class PasswordUtil {

  /**
   * 随机生成盐值
   *
   * @return
   */
  public static String getSalt() {
    return MD5Util.getMD5(UUIDUtil.getUUID());
  }

  /**
   * 密码加密
   *
   * @param salt
   * @param inputPassword
   * @return
   */
  public static String encryptPassword(String salt, String inputPassword) {
    return MD5Util.getMD5(MD5Util.getMD5(inputPassword) + salt);
  }
}
