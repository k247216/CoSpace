package com.rubin.rpan.storage.oss;

import com.aliyun.credentials.Client;
import com.aliyun.credentials.models.Config;
import com.aliyun.credentials.models.CredentialModel;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.auth.DefaultCredentials;
import com.aliyun.oss.common.comm.SignVersion;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@Conditional(OssClientConfig.Enabled.class)
public class OssClientConfig {
  static class Enabled extends AnyNestedCondition {
    Enabled() {
      super(ConfigurationPhase.PARSE_CONFIGURATION);
    }

    @ConditionalOnProperty(name = "rpan.storage.oss.enabled", havingValue = "true")
    static class Explicit {}

    @ConditionalOnProperty(
        name = "rpan.storage.processor.type",
        havingValue = OssStorageProcessor.TYPE)
    static class DefaultProcessor {}
  }

  @Bean(destroyMethod = "shutdown")
  OSS ossClient(
      @Value("${rpan.storage.oss.region}") String region,
      @Value("${rpan.storage.oss.endpoint:}") String endpoint,
      @Value("${rpan.storage.oss.cname:false}") boolean cname,
      @Value("${rpan.storage.oss.ram-role:}") String ramRole,
      @Value("${rpan.storage.oss.access-key-id:${OSS_ACCESS_KEY_ID:}}") String accessKeyId,
      @Value("${rpan.storage.oss.access-key-secret:${OSS_ACCESS_KEY_SECRET:}}")
          String accessKeySecret,
      @Value("${rpan.storage.oss.security-token:${OSS_SESSION_TOKEN:}}") String token) {
    if (!region.matches("[a-z0-9]+(?:-[a-z0-9]+)+")) {
      throw new IllegalArgumentException("Configure the OSS bucket region, such as cn-hangzhou");
    }
    if (cname && endpoint.isBlank()) {
      throw new IllegalArgumentException("Configure the OSS custom domain endpoint");
    }
    String address = endpoint.isBlank() ? "https://oss-" + region + ".aliyuncs.com" : endpoint;
    URI uri = URI.create(address);
    if (!"https".equals(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !uri.getPath().isEmpty() && !"/".equals(uri.getPath())) {
      throw new IllegalArgumentException("OSS endpoint must be an HTTPS service endpoint");
    }

    CredentialsProvider credentials;
    if (!ramRole.isBlank()) {
      credentials = instanceRoleCredentials(ramRole);
    } else {
      if (accessKeyId.isBlank() || accessKeySecret.isBlank()) {
        throw new IllegalArgumentException("Configure an ECS RAM role or OSS access credentials");
      }
      credentials =
          token.isBlank()
              ? new DefaultCredentialProvider(accessKeyId, accessKeySecret)
              : new DefaultCredentialProvider(accessKeyId, accessKeySecret, token);
    }

    ClientBuilderConfiguration config = new ClientBuilderConfiguration();
    config.setSignatureVersion(SignVersion.V4);
    config.setSupportCname(cname);
    config.setConnectionTimeout(5_000);
    config.setSocketTimeout(60_000);
    return OSSClientBuilder.create()
        .endpoint(address)
        .region(region)
        .credentialsProvider(credentials)
        .clientConfiguration(config)
        .build();
  }

  @Bean
  OssStorageProcessor ossStorageProcessor(
      OSS ossClient, @Value("${rpan.storage.oss.bucket-name}") String bucket) {
    return new OssStorageProcessor(ossClient, bucket);
  }

  private static CredentialsProvider instanceRoleCredentials(String role) {
    Config config = new Config();
    config.setType("ecs_ram_role");
    config.setRoleName(role);
    config.setDisableIMDSv1(true);
    Client credentialsClient = new Client(config);
    return new CredentialsProvider() {
      @Override
      public void setCredentials(Credentials credentials) {
        throw new UnsupportedOperationException(
            "Instance role credentials are managed by Alibaba Cloud");
      }

      @Override
      public Credentials getCredentials() {
        CredentialModel credentials = credentialsClient.getCredential();
        return new DefaultCredentials(
            credentials.getAccessKeyId(),
            credentials.getAccessKeySecret(),
            credentials.getSecurityToken());
      }
    };
  }
}
