package com.rubin.rpan.modules.file;

import com.rubin.rpan.modules.file.controller.UploadTaskController;
import com.rubin.rpan.modules.file.dao.*;
import com.rubin.rpan.modules.file.upload.concurrent.UploadLocks;
import com.rubin.rpan.modules.file.upload.config.UploadProperties;
import com.rubin.rpan.modules.file.upload.config.UploadSchedulingConfig;
import com.rubin.rpan.modules.file.upload.job.UploadTaskCleaner;
import com.rubin.rpan.modules.file.upload.model.UploadTaskView;
import com.rubin.rpan.modules.file.upload.service.UploadCompletionService;
import com.rubin.rpan.modules.file.upload.service.UploadTaskService;
import com.rubin.rpan.modules.file.upload.storage.RedisUploadProgressStore;
import com.rubin.rpan.modules.file.upload.storage.UploadChunkStorage;
import org.junit.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

public class UploadTaskWiringTest {
  @Test
  public void componentsBindAndCreateTaskWithoutWritingDiskOrRedis() {
    new ApplicationContextRunner()
        .withUserConfiguration(Wiring.class)
        .withPropertyValues(
            "rpan.upload.init-limit.enabled=false",
            "rpan.upload.staging-path=/unused-upload",
            "rpan.upload.chunk-size=2",
            "rpan.storage.local.root-file-path=/unused-final",
            "rpan.storage.processor.type=" + UploadTaskFixture.LOCAL)
        .run(
            context -> {
              Assert.assertNull(context.getStartupFailure());
              Assert.assertNotNull(context.getBean(UploadTaskController.class));
              Assert.assertNotNull(context.getBean(UploadTaskCleaner.class));
              Assert.assertEquals(
                  1,
                  context
                      .getBean(org.springframework.scheduling.config.ScheduledTaskHolder.class)
                      .getScheduledTasks()
                      .size());
              org.mockito.Mockito.clearInvocations(context.getBean(StringRedisTemplate.class));
              UploadTaskView view =
                  context
                      .getBean(UploadTaskService.class)
                      .initialize(context.getBean(UploadTaskFixture.class).input(5), 1L);
              Assert.assertEquals(3, view.getTotalChunks().intValue());
              org.mockito.Mockito.verifyNoInteractions(context.getBean(StringRedisTemplate.class));
            });
  }

  @Test
  public void uploadSweepCanBeDisabledIndependently() {
    new ApplicationContextRunner()
        .withUserConfiguration(Wiring.class)
        .withPropertyValues(
            "rpan.upload.gc.enabled=false",
            "rpan.cleanup.enabled=false",
            "rpan.storage.local.root-file-path=/unused-final",
            "rpan.storage.processor.type=" + UploadTaskFixture.LOCAL)
        .run(
            context -> {
              Assert.assertNull(context.getStartupFailure());
              Assert.assertTrue(context.getBeansOfType(UploadTaskCleaner.class).isEmpty());
            });
  }

  @Configuration
  @EnableConfigurationProperties({
    UploadProperties.class,
    com.rubin.rpan.modules.file.upload.limit.UploadRateLimitProperties.class
  })
  @Import({
    com.rubin.rpan.storage.ObjectUploadStorageRegistry.class,
    com.rubin.rpan.modules.file.upload.limit.UploadInitRateLimiter.class,
    UploadSchedulingConfig.class,
    UploadTaskCleaner.class,
    UploadCompletionService.class,
    UploadTaskService.class,
    UploadChunkStorage.class,
    UploadLocks.class,
    RedisUploadProgressStore.class,
    UploadTaskController.class
  })
  static class Wiring {
    @Bean(destroyMethod = "close")
    UploadTaskFixture fixture() throws Exception {
      return new UploadTaskFixture(false);
    }

    @Bean(name = "rPanUploadTaskMapper")
    RPanUploadTaskMapper tasks(UploadTaskFixture f) {
      return f.tasks;
    }

    @Bean(name = "rPanUserFileMapper")
    RPanUserFileMapper users(UploadTaskFixture f) {
      return f.database.session.getMapper(RPanUserFileMapper.class);
    }

    @Bean(name = "rPanFileMapper")
    RPanFileMapper files(UploadTaskFixture f) {
      return f.database.session.getMapper(RPanFileMapper.class);
    }

    @Bean
    PlatformTransactionManager manager(UploadTaskFixture f) {
      return new DataSourceTransactionManager(f.database.dataSource);
    }

    @Bean
    com.rubin.rpan.modules.file.quota.service.UserSpaceService space(UploadTaskFixture f) {
      return f.database.space;
    }

    @Bean
    StringRedisTemplate redis() {
      return org.mockito.Mockito.mock(StringRedisTemplate.class);
    }
  }
}
