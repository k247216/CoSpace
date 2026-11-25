package com.rubin.rpan.storage.s3;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rpan.storage.s3.enabled", havingValue = "true")
class S3StorageConfig {
  @Bean(destroyMethod = "close")
  S3Client coSpaceS3Client(
      @Value("${rpan.storage.s3.region:us-east-1}") String region,
      @Value("${rpan.storage.s3.endpoint:}") String endpoint,
      @Value("${rpan.storage.s3.path-style:false}") boolean pathStyle) {
    var builder =
        S3Client.builder()
            .region(Region.of(region))
            .forcePathStyle(pathStyle)
            .httpClientBuilder(
                UrlConnectionHttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(5))
                    .socketTimeout(Duration.ofSeconds(60)))
            .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofMinutes(20)));
    if (!endpoint.isBlank()) {
      builder.endpointOverride(URI.create(endpoint));
    }
    return builder.build();
  }

  @Bean
  S3StorageProcessor s3StorageProcessor(
      S3Client coSpaceS3Client, @Value("${rpan.storage.s3.bucket}") String bucket) {
    return new S3StorageProcessor(coSpaceS3Client, bucket);
  }
}
