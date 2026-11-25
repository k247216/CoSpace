package com.rubin.rpan.modules.file;

import com.rubin.rpan.common.exception.RPanException;
import com.rubin.rpan.modules.file.dao.RPanUserFileMapper;
import java.util.Collections;
import java.util.concurrent.*;
import org.junit.*;

public class FileLifecycleMySqlTest {
  private FileLifecycleFixture f;
  private ExecutorService workers;

  @Before
  public void setUp() throws Exception {
    Assume.assumeTrue(
        "Real MySQL tests require cleanup.mysql.url",
        System.getProperty("cleanup.mysql.url") != null);
    f = new FileLifecycleFixture(true);
    workers = Executors.newFixedThreadPool(2);
  }

  @After
  public void tearDown() throws Exception {
    if (workers != null) {
      workers.shutdownNow();
      workers.awaitTermination(10, TimeUnit.SECONDS);
    }
    if (f != null) f.close();
  }

  @Test
  public void newReferenceFirstProtectsFileEvenWithOldRepeatableReadSnapshot() throws Exception {
    f.jdbc.update("UPDATE r_pan_user_file SET del_flag=1 WHERE file_id=101");
    CountDownLatch writerReady = new CountDownLatch(1),
        release = new CountDownLatch(1),
        oldSnapshot = new CountDownLatch(1);
    Future<?> writer =
        workers.submit(
            () ->
                f.tx.execute(
                    status -> {
                      f.lifecycle.lockActiveFiles(Collections.singleton(900L));
                      f.reference(201, 2, 200, 900, 0, 0);
                      writerReady.countDown();
                      await(release);
                      return null;
                    }));
    try {
      await(writerReady);
      Future<?> cleaner =
          workers.submit(
              () ->
                  f.tx.execute(
                      status -> {
                        // This ordinary SELECT deliberately establishes an old read view before B
                        // commits.
                        Assert.assertEquals(
                            1,
                            f.session
                                .getMapper(RPanUserFileMapper.class)
                                .selectCountByRealFileId(900L));
                        oldSnapshot.countDown();
                        f.service.physicalDeleteUserFiles("101", 1L);
                        return null;
                      }));
      await(oldSnapshot);
      assertWaiting(cleaner);
      release.countDown();
      writer.get(10, TimeUnit.SECONDS);
      cleaner.get(10, TimeUnit.SECONDS);
      Assert.assertEquals("ACTIVE", f.state());
      Assert.assertEquals(0, f.tasks());
      Assert.assertEquals(
          1,
          f.jdbc
              .queryForObject(
                  "SELECT COUNT(*) FROM r_pan_user_file WHERE real_file_id=900", Integer.class)
              .intValue());
    } finally {
      release.countDown();
    }
  }

  @Test
  public void cleanupFirstMakesWaitingSecUploadMissInsteadOfCreatingDanglingReference()
      throws Exception {
    cleanupFirst(false);
  }

  @Test
  public void cleanupFirstRejectsWaitingCopy() throws Exception {
    cleanupFirst(true);
  }

  private void cleanupFirst(boolean copy) throws Exception {
    CountDownLatch cleanupReady = new CountDownLatch(1),
        release = new CountDownLatch(1),
        requestStarted = new CountDownLatch(1);
    Future<?> cleaner =
        workers.submit(
            () ->
                f.tx.execute(
                    status -> {
                      f.jdbc.update("UPDATE r_pan_user_file SET del_flag=1 WHERE file_id=101");
                      f.service.physicalDeleteUserFiles("101", 1L);
                      cleanupReady.countDown();
                      await(release);
                      return null;
                    }));
    try {
      await(cleanupReady);
      Future<Boolean> requester =
          workers.submit(
              () ->
                  f.tx.execute(
                      status -> {
                        requestStarted.countDown();
                        if (!copy)
                          return f.service.secUpload(
                              100L, "copy.txt", FileLifecycleFixture.MD5, 10L, 1L);
                        try {
                          f.service.copy("101", 100L, 100L, 1L);
                          return true;
                        } catch (RPanException expected) {
                          return false;
                        }
                      }));
      await(requestStarted);
      assertWaiting(requester);
      release.countDown();
      cleaner.get(10, TimeUnit.SECONDS);
      Assert.assertFalse(requester.get(10, TimeUnit.SECONDS));
      Assert.assertEquals("DELETING", f.state());
      Assert.assertEquals(1, f.tasks());
      Assert.assertEquals(
          0,
          f.jdbc
              .queryForObject(
                  "SELECT COUNT(*) FROM r_pan_user_file WHERE real_file_id=900", Integer.class)
              .intValue());
    } finally {
      release.countDown();
    }
  }

  @Test
  public void concurrentSchedulingCreatesOnlyOneTask() throws Exception {
    f.jdbc.update("DELETE FROM r_pan_user_file WHERE file_id=101");
    CountDownLatch start = new CountDownLatch(1);
    Callable<Void> work =
        () -> {
          await(start);
          f.tx.execute(
              status -> {
                f.lifecycle.scheduleIfUnreferenced(Collections.singleton(900L));
                return null;
              });
          return null;
        };
    Future<Void> first = workers.submit(work), second = workers.submit(work);
    start.countDown();
    first.get(10, TimeUnit.SECONDS);
    second.get(10, TimeUnit.SECONDS);
    Assert.assertEquals("DELETING", f.state());
    Assert.assertEquals(1, f.tasks());
  }

  @Test
  public void mysqlConsumerCompletesAndLateDeliveryResultPreservesSuccess() throws Exception {
    java.nio.file.Path root = java.nio.file.Files.createTempDirectory("rpan-mysql-cleanup-");
    java.nio.file.Path content =
        java.nio.file.Files.write(root.resolve("file.txt"), new byte[] {1});
    try {
      f.jdbc.update("UPDATE r_pan_file SET real_path=? WHERE file_id=900", content.toString());
      f.jdbc.update("DELETE FROM r_pan_user_file WHERE file_id=101");
      f.tx.execute(
          s -> {
            f.lifecycle.scheduleIfUnreferenced(Collections.singleton(900L));
            return null;
          });
      com.rubin.rpan.modules.file.cleanup.config.CleanupProperties p =
          new com.rubin.rpan.modules.file.cleanup.config.CleanupProperties();
      com.rubin.rpan.modules.file.dao.RPanFileCleanupTaskMapper tasks =
          f.session.getMapper(com.rubin.rpan.modules.file.dao.RPanFileCleanupTaskMapper.class);
      com.rubin.rpan.modules.file.cleanup.service.CleanupTaskExecutor executor =
          new com.rubin.rpan.modules.file.cleanup.service.CleanupTaskExecutor(
              tasks,
              f.session.getMapper(com.rubin.rpan.modules.file.dao.RPanFileMapper.class),
              f.session.getMapper(RPanUserFileMapper.class),
              new com.rubin.rpan.modules.file.cleanup.storage.LocalCleanupStorageDeleter(
                  "com.rubin.rpan.storage.local.LocalStorageProcessor", root.toString()),
              p,
              new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.dataSource));
      com.rubin.rpan.modules.file.cleanup.mq.CleanupTaskPublisher pub =
          new com.rubin.rpan.modules.file.cleanup.mq.CleanupTaskPublisher(
              tasks, id -> Assert.assertTrue(executor.execute(id)), p);
      pub.setClock(
          java.time.Clock.fixed(java.time.Instant.now().plusSeconds(1), java.time.ZoneOffset.UTC));
      pub.publishDue();
      Assert.assertEquals("DELETED", f.state());
      Assert.assertFalse(java.nio.file.Files.exists(content));
      Assert.assertEquals(
          "SUCCEEDED",
          f.jdbc.queryForObject(
              "SELECT execution_state FROM r_pan_file_cleanup_task", String.class));
      Assert.assertEquals(
          "SENT",
          f.jdbc.queryForObject(
              "SELECT delivery_state FROM r_pan_file_cleanup_task", String.class));
      long id = tasks.selectByRealFileId(900L).getTaskId();
      Assert.assertTrue(executor.execute(id));
    } finally {
      java.nio.file.Files.deleteIfExists(content);
      java.nio.file.Files.deleteIfExists(root);
    }
  }

  private static void assertWaiting(Future<?> future) throws Exception {
    try {
      future.get(200, TimeUnit.MILLISECONDS);
      Assert.fail("Expected transaction to wait for physical file row lock");
    } catch (TimeoutException expected) {
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(8, TimeUnit.SECONDS))
        throw new AssertionError("Timed out waiting for test boundary");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
