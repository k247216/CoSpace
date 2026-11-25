package com.rubin.rpan.modules.file;

import com.rubin.rpan.modules.file.cleanup.config.CleanupProperties;
import com.rubin.rpan.modules.file.cleanup.config.CleanupSchedulingConfig;
import com.rubin.rpan.modules.file.cleanup.mq.CleanupTaskPublisher;
import com.rubin.rpan.modules.file.cleanup.mq.RocketMqCleanupTransport;
import com.rubin.rpan.modules.file.cleanup.service.CleanupTaskExecutor;
import com.rubin.rpan.modules.file.cleanup.storage.LocalCleanupStorageDeleter;
import com.rubin.rpan.modules.file.dao.*;
import org.junit.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

public class CleanupWiringTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(Wiring.class)
          .withPropertyValues(
              "rpan.storage.processor.type=com.rubin.rpan.storage.local.LocalStorageProcessor",
              "rpan.storage.local.root-file-path=/unused",
              "rpan.cleanup.max-attempts=2");

  @Test
  public void enabledCleanupBindsPropertiesAndStartsWithoutBrokerConnection() {
    runner
        .withPropertyValues("rpan.cleanup.enabled=true")
        .run(
            context -> {
              Assert.assertNull(context.getStartupFailure());
              Assert.assertNotNull(context.getBean(CleanupTaskPublisher.class));
              Assert.assertNotNull(context.getBean(RocketMqCleanupTransport.class));
              Assert.assertEquals(2, context.getBean(CleanupProperties.class).getMaxAttempts());
            });
  }

  @Test
  public void disabledCleanupDoesNotCreatePublisherOrMqClient() {
    runner
        .withPropertyValues("rpan.cleanup.enabled=false")
        .run(
            context -> {
              Assert.assertNull(context.getStartupFailure());
              Assert.assertEquals(0, context.getBeansOfType(CleanupTaskPublisher.class).size());
              Assert.assertEquals(0, context.getBeansOfType(RocketMqCleanupTransport.class).size());
            });
  }

  @Configuration
  @EnableConfigurationProperties(CleanupProperties.class)
  @Import({
    CleanupSchedulingConfig.class,
    CleanupTaskPublisher.class,
    RocketMqCleanupTransport.class,
    CleanupTaskExecutor.class,
    LocalCleanupStorageDeleter.class
  })
  static class Wiring {
    @Bean(destroyMethod = "close")
    FileLifecycleFixture fixture() throws Exception {
      return new FileLifecycleFixture(false);
    }

    @Bean(name = "rPanFileMapper")
    RPanFileMapper files(FileLifecycleFixture f) {
      return f.session.getMapper(RPanFileMapper.class);
    }

    @Bean(name = "rPanUserFileMapper")
    RPanUserFileMapper references(FileLifecycleFixture f) {
      return f.session.getMapper(RPanUserFileMapper.class);
    }

    @Bean
    RPanFileCleanupTaskMapper tasks(FileLifecycleFixture f) {
      return f.session.getMapper(RPanFileCleanupTaskMapper.class);
    }

    @Bean
    PlatformTransactionManager manager(FileLifecycleFixture f) {
      return new DataSourceTransactionManager(f.dataSource);
    }
  }
}
