package com.rubin.rpan.modules.file;

import com.rubin.rpan.modules.file.upload.limit.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

public class UploadRateLimitRedisTest {
  private LettuceConnectionFactory factory;
  private StringRedisTemplate redis;
  private LettuceConnectionFactory secondFactory;
  private final List<String> keys = new ArrayList<>();

  @Before
  public void setUp() {
    Assume.assumeTrue("Requires opt-in local Redis", Boolean.getBoolean("upload.redis.test"));
    factory = new LettuceConnectionFactory(RedisTestSupport.configuration());
    factory.setDatabase(15);
    factory.afterPropertiesSet();
    redis = new StringRedisTemplate(factory);
    redis.afterPropertiesSet();
  }

  @After
  public void tearDown() {
    if (redis != null) for (String key : keys) redis.delete(key);
    if (secondFactory != null) secondFactory.destroy();
    if (factory != null) factory.destroy();
  }

  private long user() {
    long id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    keys.add(UploadInitRateLimiter.key(id));
    return id;
  }

  private UploadInitRateLimiter limiter(int capacity, long interval) {
    UploadRateLimitProperties p = new UploadRateLimitProperties();
    p.setCapacity(capacity);
    p.setRefillIntervalMs(interval);
    return new UploadInitRateLimiter(redis, p);
  }

  @Test
  public void twoClientsShareExactlyThreeTokensAcrossHundredRequests() throws Exception {
    long user = user();
    UploadInitRateLimiter a = limiter(3, 60000);
    secondFactory = new LettuceConnectionFactory(RedisTestSupport.configuration());
    secondFactory.setDatabase(15);
    secondFactory.afterPropertiesSet();
    StringRedisTemplate secondRedis = new StringRedisTemplate(secondFactory);
    secondRedis.afterPropertiesSet();
    UploadRateLimitProperties p = new UploadRateLimitProperties();
    p.setCapacity(3);
    p.setRefillIntervalMs(60000);
    UploadInitRateLimiter b = new UploadInitRateLimiter(secondRedis, p);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    try {
      for (int i = 0; i < 100; i++) {
        UploadInitRateLimiter client = i % 2 == 0 ? a : b;
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    client.check(user);
                    return true;
                  } catch (UploadLimitException e) {
                    Assert.assertFalse(e.isUnavailable());
                    return false;
                  }
                }));
      }
      start.countDown();
      int allowed = 0;
      for (Future<Boolean> result : results) if (result.get(15, TimeUnit.SECONDS)) allowed++;
      Assert.assertEquals(3, allowed);
      Assert.assertTrue(
          redis.getExpire(UploadInitRateLimiter.key(user), TimeUnit.MILLISECONDS) > 0);
      b.check(user()); // 另一用户有自己的桶。
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void refillAndIdleExpiryRestoreCapacity() throws Exception {
    long user = user();
    UploadInitRateLimiter limiter = limiter(1, 100);
    limiter.check(user);
    try {
      limiter.check(user);
      Assert.fail();
    } catch (UploadLimitException expected) {
      Assert.assertFalse(expected.isUnavailable());
    }
    Thread.sleep(140);
    limiter.check(user);
    Thread.sleep(1200);
    Assert.assertFalse(redis.hasKey(UploadInitRateLimiter.key(user)));
    limiter.check(user);
  }
}
