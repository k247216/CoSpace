package com.rubin.rpan.modules.file;

import com.rubin.rpan.modules.file.cleanup.config.CleanupProperties;
import com.rubin.rpan.modules.file.cleanup.mq.RocketMqCleanupTransport;
import com.rubin.rpan.modules.file.cleanup.service.CleanupTaskExecutor;
import com.rubin.rpan.modules.file.cleanup.storage.CleanupStorageDeleter;
import com.rubin.rpan.modules.file.cleanup.storage.LocalCleanupStorageDeleter;
import com.rubin.rpan.modules.file.cleanup.storage.UnsafeCleanupException;
import com.rubin.rpan.modules.file.dao.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class CleanupExecutionTest {
  private FileLifecycleFixture f;
  private CleanupProperties p;
  private long id;

  @Before
  public void setUp() throws Exception {
    f = new FileLifecycleFixture(false);
    p = new CleanupProperties();
    f.jdbc.update("DELETE FROM r_pan_user_file WHERE file_id=101");
    f.tx.execute(
        status -> {
          f.lifecycle.scheduleIfUnreferenced(Collections.singleton(900L));
          return null;
        });
    id = f.session.getMapper(RPanFileCleanupTaskMapper.class).selectByRealFileId(900L).getTaskId();
  }

  @After
  public void tearDown() {
    if (f != null) f.close();
  }

  private CleanupTaskExecutor executor(CleanupStorageDeleter storage) {
    return new CleanupTaskExecutor(
        f.session.getMapper(RPanFileCleanupTaskMapper.class),
        f.session.getMapper(RPanFileMapper.class),
        f.session.getMapper(RPanUserFileMapper.class),
        storage,
        p,
        new DataSourceTransactionManager(f.dataSource));
  }

  private String state() {
    return f.jdbc.queryForObject(
        "SELECT execution_state FROM r_pan_file_cleanup_task", String.class);
  }

  private int attempts() {
    return f.jdbc.queryForObject(
        "SELECT execution_attempts FROM r_pan_file_cleanup_task", Integer.class);
  }

  @Test
  public void duplicateMessageOnlyDeletesOnceAndIoIsOutsideTransaction() {
    AtomicInteger calls = new AtomicInteger();
    CleanupTaskExecutor e =
        executor(
            task -> {
              Assert.assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              calls.incrementAndGet();
            });
    Assert.assertTrue(e.execute(id));
    Assert.assertTrue(e.execute(id));
    Assert.assertEquals(1, calls.get());
    Assert.assertEquals(1, attempts());
    Assert.assertEquals("DELETED", f.state());
    Assert.assertEquals("SUCCEEDED", state());
  }

  @Test
  public void failedDeletionIsRetriedThenCompletes() {
    AtomicInteger calls = new AtomicInteger();
    CleanupTaskExecutor e =
        executor(
            task -> {
              if (calls.incrementAndGet() == 1) throw new IOException("injected");
            });
    Assert.assertFalse(e.execute(id));
    Assert.assertEquals("RETRY", state());
    Assert.assertEquals("DELETING", f.state());
    Assert.assertTrue(
        f.jdbc
            .queryForObject("SELECT last_error FROM r_pan_file_cleanup_task", String.class)
            .contains("IOException"));
    Assert.assertTrue(e.execute(id));
    Assert.assertEquals(2, attempts());
    Assert.assertEquals("SUCCEEDED", state());
  }

  @Test
  public void exhaustedTaskRequiresExplicitRecovery() {
    p.setMaxAttempts(2);
    AtomicBoolean fail = new AtomicBoolean(true);
    CleanupTaskExecutor e =
        executor(
            task -> {
              if (fail.get()) throw new IOException();
            });
    Assert.assertFalse(e.execute(id));
    Assert.assertTrue(e.execute(id));
    Assert.assertEquals("MANUAL", state());
    Assert.assertTrue(e.execute(id));
    Assert.assertEquals(2, attempts());
    Assert.assertTrue(e.retryManual(id));
    Assert.assertEquals("DELETING", f.state());
    fail.set(false);
    Assert.assertTrue(e.execute(id));
    Assert.assertEquals("SUCCEEDED", state());
  }

  @Test
  public void unsafeStorageIsManualWithoutAutomaticRetries() {
    CleanupTaskExecutor e =
        executor(
            task -> {
              throw new UnsafeCleanupException("PATH_OUTSIDE_ROOT");
            });
    Assert.assertTrue(e.execute(id));
    Assert.assertEquals("MANUAL", state());
    Assert.assertEquals(1, attempts());
  }

  @Test
  public void activeOrStillReferencedFileCannotBeDeleted() {
    AtomicInteger calls = new AtomicInteger();
    CleanupTaskExecutor e = executor(task -> calls.incrementAndGet());
    f.reference(201, 2, 200, 900, 0, 0);
    Assert.assertTrue(e.execute(id));
    Assert.assertEquals("MANUAL", state());
    Assert.assertEquals(0, calls.get());
    Assert.assertFalse(e.retryManual(id));
  }

  @Test
  public void invalidFileStateCannotTriggerStorage() {
    f.jdbc.update("UPDATE r_pan_file SET lifecycle_state='ACTIVE' WHERE file_id=900");
    CleanupTaskExecutor e = executor(task -> Assert.fail("Must not delete active content"));
    Assert.assertTrue(e.execute(id));
    Assert.assertEquals("MANUAL", state());
  }

  @Test
  public void storageTypeMismatchCannotDeleteOrRequeueTheTask() {
    f.jdbc.update("UPDATE r_pan_file SET storage_type='other-backend' WHERE file_id=900");
    CleanupTaskExecutor executor =
        executor(task -> Assert.fail("Mismatched storage type must not reach storage"));
    Assert.assertTrue(executor.execute(id));
    Assert.assertEquals("MANUAL", state());
    Assert.assertEquals(0, attempts());
    Assert.assertFalse(executor.retryManual(id));
  }

  @Test
  public void unknownTaskDoesNotDeleteAnything() {
    Assert.assertTrue(executor(task -> Assert.fail("Unknown task")).execute(123L));
    Assert.assertEquals("PENDING", state());
  }

  @Test
  public void unexpiredLeaseSkipsDuplicateButExpiredLeaseRecovers() {
    Instant now = Instant.now();
    f.jdbc.update(
        "UPDATE r_pan_file_cleanup_task SET"
            + " execution_state='PROCESSING',execution_attempts=1,lease_owner='old',lease_until=?",
        Date.from(now.plusSeconds(60)));
    AtomicInteger calls = new AtomicInteger();
    CleanupTaskExecutor e = executor(task -> calls.incrementAndGet());
    e.setClock(Clock.fixed(now, ZoneOffset.UTC));
    Assert.assertTrue(e.execute(id));
    Assert.assertEquals(0, calls.get());
    e.setClock(Clock.fixed(now.plusSeconds(61), ZoneOffset.UTC));
    Assert.assertTrue(e.execute(id));
    Assert.assertEquals(1, calls.get());
    Assert.assertEquals(2, attempts());
    Assert.assertEquals("SUCCEEDED", state());
  }

  @Test
  public void staleExecutorCannotOverwriteNewOwnersSuccess() {
    Instant now = Instant.now();
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<CleanupTaskExecutor> ref = new AtomicReference<>();
    CleanupTaskExecutor e =
        executor(
            task -> {
              if (calls.incrementAndGet() == 1) {
                ref.get().setClock(Clock.fixed(now.plusMillis(p.getLeaseMs() + 1), ZoneOffset.UTC));
                Assert.assertTrue(ref.get().execute(id));
                throw new IOException("old executor finishes late");
              }
            });
    ref.set(e);
    e.setClock(Clock.fixed(now, ZoneOffset.UTC));
    Assert.assertTrue(e.execute(id));
    Assert.assertEquals(2, attempts());
    Assert.assertEquals("SUCCEEDED", state());
    Assert.assertNull(
        f.jdbc.queryForObject("SELECT last_error FROM r_pan_file_cleanup_task", String.class));
  }

  @Test
  public void fileAlreadyDeletedBeforeResultSavedCanCompleteAfterLeaseExpires() throws Exception {
    Path root = Files.createTempDirectory("rpan-recovery-");
    Path file = root.resolve("content.txt");
    try {
      Files.write(file, new byte[] {1});
      f.jdbc.update("UPDATE r_pan_file SET real_path=? WHERE file_id=900", file.toString());
      f.jdbc.update(
          "UPDATE r_pan_file_cleanup_task SET"
              + " storage_locator=?,execution_state='PROCESSING',execution_attempts=1,lease_owner='crashed',lease_until=?",
          file.toString(),
          new Date(0));
      Files.delete(file); // 模拟内容已删、进程尚未保存结果就退出。
      CleanupTaskExecutor e =
          executor(
              new LocalCleanupStorageDeleter(
                  "com.rubin.rpan.storage.local.LocalStorageProcessor", root.toString()));
      Assert.assertTrue(e.execute(id));
      Assert.assertEquals("SUCCEEDED", state());
      Assert.assertEquals("DELETED", f.state());
    } finally {
      Files.deleteIfExists(file);
      Files.deleteIfExists(root);
    }
  }

  @Test
  public void callerCannotWrapStorageDeletionInOuterTransaction() {
    CleanupTaskExecutor e =
        executor(task -> Assert.fail("Storage must not run inside a caller transaction"));
    try {
      f.tx.execute(status -> e.execute(id));
      Assert.fail("Expected transaction boundary rejection");
    } catch (IllegalStateException expected) {
    }
    Assert.assertEquals("PENDING", state());
    Assert.assertEquals(0, attempts());
  }

  @Test
  public void malformedMessagesAreRejected() {
    Assert.assertEquals(
        501,
        RocketMqCleanupTransport.decodeTaskId(
            "1:501".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    for (String value :
        new String[] {"501", "2:501", "1:-1", "1:0", "1:9999999999999999999", "1:1:/etc/passwd"}) {
      try {
        RocketMqCleanupTransport.decodeTaskId(
            value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Assert.fail(value);
      } catch (IllegalArgumentException expected) {
      }
    }
  }
}
