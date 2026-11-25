package com.rubin.rpan.modules.file;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.rubin.rpan.modules.file.dao.*;
import com.rubin.rpan.modules.file.entity.RPanUploadTask;
import com.rubin.rpan.modules.file.upload.job.UploadTaskCleaner;
import com.rubin.rpan.modules.file.upload.model.UploadTaskState;
import com.rubin.rpan.modules.file.upload.storage.UploadChunkStorage;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import org.junit.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;

public class UploadTaskCleanerTest {
  protected UploadTaskFixture f;
  private Clock clock;

  protected boolean mysql() {
    return false;
  }

  @Before
  public void setUp() throws Exception {
    f = new UploadTaskFixture(mysql());
    clock = Clock.fixed(Instant.now().plus(Duration.ofDays(10)), ZoneOffset.UTC);
  }

  @After
  public void tearDown() throws Exception {
    if (f != null) f.close();
  }

  private UploadTaskCleaner cleaner(RPanUploadTaskMapper mapper, UploadChunkStorage storage) {
    UploadTaskCleaner c =
        new UploadTaskCleaner(
            mapper,
            f.database.session.getMapper(RPanFileMapper.class),
            storage,
            f.progress,
            f.locks,
            f.properties,
            new DataSourceTransactionManager(f.database.dataSource),
            f.root.resolve("final").toString(),
            f.database.space,
            UploadTaskFixture.LOCAL_ONLY);
    c.setClock(clock);
    return c;
  }

  private UploadTaskCleaner cleaner() {
    return cleaner(f.tasks, f.storage);
  }

  private String receive() {
    String id = f.service.initialize(f.input(3), 1L).getUploadId();
    f.service.receive(id, 0, new MockMultipartFile("file", new byte[] {1, 2}), 1L);
    return id;
  }

  private Path finalFile(String id) throws Exception {
    Path directory = f.root.resolve("final/uploaded");
    Files.createDirectories(directory);
    Path file = directory.toRealPath().resolve(id);
    Files.write(file, new byte[] {1, 2, 3});
    f.database.jdbc.update(
        "UPDATE r_pan_upload_task SET final_path=? WHERE upload_id=?", file.toString(), id);
    return file;
  }

  private RPanUploadTask task(String id) {
    return f.tasks.selectById(id);
  }

  @Test
  public void expiredUploadRemovesChunksIncomingAndBitmapAndRetainsTask() throws Exception {
    String id = receive();
    Files.write(f.root.resolve(id).resolve("incoming/half.tmp"), new byte[] {1});
    UploadChunkStorage checked = spy(f.storage);
    doAnswer(
            inv -> {
              Assert.assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              Assert.assertEquals(UploadTaskState.EXPIRED, task(id).getTaskState());
              f.storage.removeChunks((RPanUploadTask) inv.getArgument(0));
              return null;
            })
        .when(checked)
        .removeChunks(any());
    Assert.assertTrue(cleaner(f.tasks, checked).cleanOne(id));
    Assert.assertEquals(UploadTaskState.EXPIRED, task(id).getTaskState());
    Assert.assertNotNull(task(id).getArtifactsCleanedAt());
    Assert.assertNull(f.progress.read(id));
    Assert.assertFalse(Files.exists(f.root.resolve(id)));
    Assert.assertFalse(cleaner().cleanOne(id)); // 已登记完成，不会重复扫描删除。
  }

  @Test
  public void activeUploadIsUntouched() {
    String id = receive();
    UploadTaskCleaner c = cleaner();
    c.setClock(Clock.systemUTC());
    Assert.assertFalse(c.cleanOne(id));
    Assert.assertTrue(Files.exists(f.part(id, 0)));
    Assert.assertEquals(UploadTaskState.UPLOADING, task(id).getTaskState());
  }

  @Test
  public void recentlyUpdatedFinalizingTaskKeepsRecoveryWindow() throws Exception {
    String id = receive();
    Path file = finalFile(id);
    f.database.jdbc.update(
        "UPDATE r_pan_upload_task SET task_state='FINALIZING',updated_at=? WHERE upload_id=?",
        new Date(clock.millis() - 86400000),
        id);
    Assert.assertFalse(cleaner().cleanOne(id));
    Assert.assertTrue(Files.exists(file));
    Assert.assertTrue(Files.exists(f.part(id, 0)));
  }

  @Test
  public void abandonedFinalizingRemovesUnregisteredFinalAndOnlyOwnMergingFiles() throws Exception {
    String id = receive();
    Path file = finalFile(id);
    f.database.jdbc.update(
        "UPDATE r_pan_upload_task SET task_state='FINALIZING' WHERE upload_id=?", id);
    Path merging = Files.write(file.resolveSibling(id + "-interrupted.merging"), new byte[] {1});
    Path other =
        Files.write(file.resolveSibling(UUID.randomUUID() + "-other.merging"), new byte[] {9});
    Assert.assertTrue(cleaner().cleanOne(id));
    Assert.assertFalse(Files.exists(file));
    Assert.assertFalse(Files.exists(merging));
    Assert.assertTrue(Files.exists(other));
    Assert.assertEquals(UploadTaskState.EXPIRED, task(id).getTaskState());
  }

  @Test
  public void completedTaskKeepsFormalFileAndBusinessReference() throws Exception {
    String id = receive();
    Path file = finalFile(id);
    f.database.jdbc.update("UPDATE r_pan_file SET real_path=? WHERE file_id=900", file.toString());
    f.database.jdbc.update(
        "UPDATE r_pan_upload_task SET task_state='COMPLETED',real_file_id=900,user_file_id=101"
            + " WHERE upload_id=?",
        id);
    Files.write(file.resolveSibling(id + "-old.merging"), new byte[] {1});
    Assert.assertTrue(cleaner().cleanOne(id));
    Assert.assertTrue(Files.exists(file));
    Assert.assertNotNull(
        f.database.session.getMapper(RPanUserFileMapper.class).selectByPrimaryKey(101L));
    Assert.assertNotNull(task(id).getArtifactsCleanedAt());
    Assert.assertFalse(Files.exists(f.part(id, 0)));
  }

  @Test
  public void metadataOnAbandonedPathBlocksFormalDeletion() throws Exception {
    String id = receive();
    Path file = finalFile(id);
    f.database.jdbc.update("UPDATE r_pan_file SET real_path=? WHERE file_id=900", file.toString());
    Assert.assertFalse(cleaner().cleanOne(id));
    Assert.assertTrue(Files.exists(file));
    Assert.assertNull(task(id).getArtifactsCleanedAt());
    Assert.assertTrue(task(id).getArtifactCleanupError().contains("metadata"));
  }

  @Test
  public void diskFailureIsRecordedAndNextAttemptCanFinish() throws Exception {
    String id = receive();
    UploadChunkStorage storage = spy(f.storage);
    doThrow(new IOException("Injected disk failure"))
        .doCallRealMethod()
        .when(storage)
        .removeChunks(any());
    UploadTaskCleaner c = cleaner(f.tasks, storage);
    Assert.assertFalse(c.cleanOne(id));
    Assert.assertEquals(UploadTaskState.EXPIRED, task(id).getTaskState());
    Assert.assertNull(task(id).getArtifactsCleanedAt());
    Assert.assertTrue(task(id).getArtifactCleanupError().contains("Injected disk"));
    Assert.assertTrue(c.cleanOne(id));
    Assert.assertNotNull(task(id).getArtifactsCleanedAt());
    Assert.assertNull(task(id).getArtifactCleanupError());
  }

  @Test
  public void cacheFailureAfterDiskDeletionCanBeRetriedAfterRestart() {
    String id = receive();
    f.progress.unavailable = true;
    Assert.assertFalse(cleaner().cleanOne(id));
    Assert.assertFalse(Files.exists(f.part(id, 0)));
    Assert.assertNull(task(id).getArtifactsCleanedAt());
    f.progress.unavailable = false;
    Assert.assertTrue(cleaner().cleanOne(id));
    Assert.assertNotNull(task(id).getArtifactsCleanedAt());
  }

  @Test
  public void expireTransactionRollbackLeavesFilesAndTaskUntouched() {
    String id = receive();
    RPanUploadTaskMapper failing =
        mock(
            RPanUploadTaskMapper.class, inv -> inv.getMethod().invoke(f.tasks, inv.getArguments()));
    doAnswer(
            inv -> {
              f.tasks.expire(id, (Date) inv.getArgument(1));
              throw new IllegalStateException("Injected rollback");
            })
        .when(failing)
        .expire(any(), any());
    Assert.assertFalse(cleaner(failing, f.storage).cleanOne(id));
    Assert.assertEquals(UploadTaskState.UPLOADING, task(id).getTaskState());
    Assert.assertTrue(Files.exists(f.part(id, 0)));
  }

  @Test
  public void receiverOrFinalizerHoldingTaskLockIsSkipped() throws Exception {
    String id = receive();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Lock guard = f.locks.task(id).readLock();
      guard.lock();
      try {
        Assert.assertFalse(pool.submit(() -> cleaner().cleanOne(id)).get(5, TimeUnit.SECONDS));
      } finally {
        guard.unlock();
      }
      Lock writer = f.locks.task(id).writeLock();
      writer.lock();
      try {
        Assert.assertFalse(pool.submit(() -> cleaner().cleanOne(id)).get(5, TimeUnit.SECONDS));
      } finally {
        writer.unlock();
      }
      Assert.assertTrue(cleaner().cleanOne(id));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void configuredPathMismatchAndDirectorySymlinkAreNotFollowed() throws Exception {
    String id = receive();
    Path unrelated = Files.write(f.root.resolve("unrelated.txt"), new byte[] {7});
    f.database.jdbc.update(
        "UPDATE r_pan_upload_task SET final_path=? WHERE upload_id=?", unrelated.toString(), id);
    Assert.assertFalse(cleaner().cleanOne(id));
    Assert.assertTrue(Files.exists(unrelated));
    f.database.jdbc.update("UPDATE r_pan_upload_task SET final_path=NULL WHERE upload_id=?", id);
    Files.createDirectory(f.root.resolve("final"));
    Files.createSymbolicLink(f.root.resolve("final/uploaded"), f.root);
    Assert.assertFalse(cleaner().cleanOne(id));
    Assert.assertTrue(Files.exists(unrelated));
  }

  @Test
  public void failedTaskCleansImmediatelyWithoutOverwritingUploadError() {
    String id = receive();
    f.database.jdbc.update(
        "UPDATE r_pan_upload_task SET task_state='FAILED',last_error='MD5 mismatch' WHERE"
            + " upload_id=?",
        id);
    UploadTaskCleaner c = cleaner();
    c.setClock(Clock.systemUTC());
    Assert.assertTrue(c.cleanOne(id));
    Assert.assertEquals("MD5 mismatch", task(id).getLastError());
  }

  @Test
  public void sweepUsesDueQueryAndHonorsEnableAndBatchLimit() {
    String a = receive(), b = receive();
    f.properties.getGc().setBatchSize(1);
    UploadTaskCleaner c = cleaner();
    f.properties.getGc().setEnabled(false);
    c.sweep();
    Assert.assertNull(task(a).getArtifactsCleanedAt());
    Assert.assertNull(task(b).getArtifactsCleanedAt());
    f.properties.getGc().setEnabled(true);
    c.sweep();
    int cleaned =
        (task(a).getArtifactsCleanedAt() == null ? 0 : 1)
            + (task(b).getArtifactsCleanedAt() == null ? 0 : 1);
    Assert.assertEquals(1, cleaned);
    c.sweep();
    Assert.assertNotNull(task(a).getArtifactsCleanedAt());
    Assert.assertNotNull(task(b).getArtifactsCleanedAt());
  }
}
