package com.rubin.rpan.modules.file.upload.limit;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class UploadInitRateLimiter {
  private static final Logger log = LoggerFactory.getLogger(UploadInitRateLimiter.class);
  private final StringRedisTemplate redis;
  private final UploadRateLimitProperties properties;
  private final DefaultRedisScript<List> script;

  public UploadInitRateLimiter(StringRedisTemplate redis, UploadRateLimitProperties properties) {
    this.redis = redis;
    this.properties = properties;
    script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("lua/upload-init-limit.lua"));
    script.setResultType(List.class);
  }

  public static String key(Long userId) {
    return "rpan:upload:init-limit:{" + userId + "}";
  }

  public void check(Long userId) {
    // 确认用户、存储配置、请求参数
    if (userId == null || userId <= 0) throw new IllegalArgumentException("限流身份必须来自登录用户");
    if (!properties.isEnabled()) return;
    final List result;
    try {
      // 利用Lua脚本进行限流  判断本次初始化是否允许
      result =
          redis.execute(
              script,
              Collections.singletonList(key(userId)),
              String.valueOf(properties.getCapacity()),
              String.valueOf(properties.getRefillIntervalMs()));
      // 第一个：是否放行，`1` 表示放行。
      // 第二个：被限流时需要等待的毫秒数。
      if (result == null
          || result.size() != 2
          || !(result.get(0) instanceof Number)
          || !(result.get(1) instanceof Number))
        throw new IllegalStateException("Invalid upload limiter response");
    } catch (RuntimeException failure) {
      log.warn("Upload initialization limiter unavailable, userId={}", userId, failure);
      throw new UploadLimitException(true, 1, failure);
    }
    // 结果正常，但是第一个数字不是1
    if (((Number) result.get(0)).longValue() != 1) {
      // 表示正常触发限流，并携带建议等待时间。如果第一个数字是 1，就不抛异常，方法正常结束，后面继续创建上传任务。
      long wait = Math.max(1, (((Number) result.get(1)).longValue() + 999) / 1000);
      log.info("Upload initialization throttled, userId={}, retryAfterSeconds={}", userId, wait);
      // 桶里没有令牌了，本次请求被限流
      throw new UploadLimitException(false, wait, null);
    }
  }
}
