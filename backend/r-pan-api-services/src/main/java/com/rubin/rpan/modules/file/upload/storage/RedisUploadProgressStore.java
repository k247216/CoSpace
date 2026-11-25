package com.rubin.rpan.modules.file.upload.storage;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 独立字符串key/原始位图，不使用业务缓存的JSON value序列化器。 */
@Component
public class RedisUploadProgressStore implements UploadProgressStore {
  private final StringRedisTemplate redis;
  private static final DefaultRedisScript<Long> MARK =
      new DefaultRedisScript<>(
          "redis.call('SETBIT',KEYS[1],ARGV[1],1);redis.call('PEXPIRE',KEYS[1],ARGV[2]);return 1",
          Long.class);
  private static final DefaultRedisScript<Long> REPLACE =
      new DefaultRedisScript<>(
          "redis.call('DEL',KEYS[1]);for i=2,#ARGV do redis.call('SETBIT',KEYS[1],ARGV[i],1) end;"
              + "if #ARGV>1 then redis.call('PEXPIRE',KEYS[1],ARGV[1]) end;return 1",
          Long.class);

  public RedisUploadProgressStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public static String key(String id) {
    return "rpan:upload:{" + id + "}:chunks";
  }

  public byte[] read(String id) {
    return redis.execute(
        (RedisCallback<byte[]>) c -> c.get(key(id).getBytes(StandardCharsets.UTF_8)));
  }

  public void mark(String id, int index, long ttlMs) {
    if (index < 0 || index >= 10000 || ttlMs <= 0)
      throw new IllegalArgumentException("Invalid bitmap parameters");
    redis.execute(
        MARK, Collections.singletonList(key(id)), String.valueOf(index), String.valueOf(ttlMs));
  }

  public void replace(String id, List<Integer> indexes, long ttlMs) {
    if (ttlMs <= 0 || indexes.stream().anyMatch(i -> i == null || i < 0 || i >= 10000))
      throw new IllegalArgumentException("Invalid bitmap parameters");
    List<String> args = new ArrayList<>();
    args.add(String.valueOf(ttlMs));
    indexes.forEach(i -> args.add(String.valueOf(i)));
    redis.execute(REPLACE, Collections.singletonList(key(id)), args.toArray());
  }

  public static List<Integer> decode(byte[] raw, int total) {
    List<Integer> result = new ArrayList<>();
    if (raw != null)
      for (int i = 0; i < total && (i >> 3) < raw.length; i++)
        if ((raw[i >> 3] & (1 << (7 - (i & 7)))) != 0) result.add(i);
    return result;
  }
}
