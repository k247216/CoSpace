package com.rubin.rpan.modules.file;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;

final class RedisTestSupport {
  private RedisTestSupport() {}

  static RedisStandaloneConfiguration configuration() {
    var configuration =
        new RedisStandaloneConfiguration(
            "127.0.0.1",
            Integer.parseInt(System.getenv().getOrDefault("RPAN_TEST_REDIS_PORT", "6379")));
    configuration.setDatabase(15);
    String password = System.getenv("RPAN_TEST_REDIS_PASSWORD");
    if (password != null && !password.isEmpty()) configuration.setPassword(password);
    return configuration;
  }
}
