package com.rubin.rpan.modules.file;

import com.rubin.rpan.modules.file.dao.*;
import com.rubin.rpan.modules.file.entity.RPanUploadTask;
import com.rubin.rpan.modules.file.upload.concurrent.UploadLocks;
import com.rubin.rpan.modules.file.upload.model.UploadTaskState;
import com.rubin.rpan.modules.file.upload.service.UploadTaskService;
import java.util.Collections;
import org.junit.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;

public class UploadTaskMySqlTest {
  private UploadTaskFixture f;

  @Before
  public void setUp() throws Exception {
    Assume.assumeTrue(
        "Requires isolated MySQL database", System.getProperty("cleanup.mysql.url") != null);
    f = new UploadTaskFixture(true);
  }

  @After
  public void tearDown() throws Exception {
    if (f != null) f.close();
  }

  @Test
  public void taskAndChunksSurviveNewServiceAndCacheLoss() {
    String id = f.service.initialize(f.input(5), 1L).getUploadId();
    f.service.receive(id, 0, new MockMultipartFile("file", new byte[] {1, 2}), 1L);
    f.progress.clear();
    UploadTaskService restarted = f.newService(f.progress, new UploadLocks());
    Assert.assertEquals(Collections.singletonList(0), restarted.status(id, 1L).getUploadedChunks());
    Assert.assertEquals(UploadTaskState.UPLOADING, f.tasks.selectById(id).getTaskState());
    Assert.assertEquals(3, f.tasks.selectById(id).getTotalChunks().intValue());
  }

  @Test
  public void initializationFailureRollsBackInsertedTask() {
    RPanUploadTaskMapper failing = org.mockito.Mockito.mock(RPanUploadTaskMapper.class);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              f.tasks.insert((RPanUploadTask) invocation.getArgument(0));
              throw new IllegalStateException("Injected failure before commit");
            })
        .when(failing)
        .insert(org.mockito.ArgumentMatchers.any());
    UploadTaskService service =
        new UploadTaskService(
            failing,
            f.database.session.getMapper(RPanUserFileMapper.class),
            f.progress,
            f.storage,
            f.locks,
            f.properties,
            new DataSourceTransactionManager(f.database.dataSource),
            UploadTaskFixture.LOCAL,
            f.database.space,
            f.limiter,
            UploadTaskFixture.LOCAL_ONLY);
    try {
      service.initialize(f.input(5), 1L);
      Assert.fail("Expected rollback");
    } catch (IllegalStateException expected) {
    }
    Assert.assertEquals(
        0,
        f.database
            .jdbc
            .queryForObject("SELECT COUNT(*) FROM r_pan_upload_task", Integer.class)
            .intValue());
  }
}
