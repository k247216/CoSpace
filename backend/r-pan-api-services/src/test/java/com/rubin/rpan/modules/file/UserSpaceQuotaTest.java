package com.rubin.rpan.modules.file;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.rubin.rpan.common.exception.RPanException;
import com.rubin.rpan.modules.file.dao.*;
import com.rubin.rpan.modules.file.entity.*;
import com.rubin.rpan.modules.file.quota.model.UserSpaceAccount;
import com.rubin.rpan.modules.file.upload.job.UploadTaskCleaner;
import com.rubin.rpan.modules.file.upload.model.*;
import com.rubin.rpan.modules.file.upload.service.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;

public class UserSpaceQuotaTest {
  protected UploadTaskFixture f;

  protected boolean mysql() {
    return false;
  }

  @Before
  public void setUp() throws Exception {
    f = new UploadTaskFixture(mysql());
  }

  @After
  public void tearDown() throws Exception {
    if (f != null) f.close();
  }

  private UserSpaceAccount account(long user) {
    return f.database.space.view(user);
  }

  private void capacity(long total) {
    f.database.jdbc.update("UPDATE r_pan_user_space SET total_bytes=? WHERE user_id=1", total);
  }

  private void reject(Runnable action) {
    try {
      action.run();
      Assert.fail("Expected rejection");
    } catch (RPanException expected) {
    }
  }

  private void work(Runnable action) {
    f.database.tx.execute(
        tx -> {
          action.run();
          return null;
        });
  }

  private UploadCompletionService completion(RPanUploadTaskMapper mapper) {
    return new UploadCompletionService(
        mapper,
        f.database.session.getMapper(RPanFileMapper.class),
        f.database.session.getMapper(RPanUserFileMapper.class),
        f.storage,
        f.progress,
        f.locks,
        new DataSourceTransactionManager(f.database.dataSource),
        f.root.resolve("final").toString(),
        f.database.space,
        UploadTaskFixture.LOCAL_ONLY);
  }

  private UploadTaskCleaner cleaner() {
    return new UploadTaskCleaner(
        f.tasks,
        f.database.session.getMapper(RPanFileMapper.class),
        f.storage,
        f.progress,
        f.locks,
        f.properties,
        new DataSourceTransactionManager(f.database.dataSource),
        f.root.resolve("final").toString(),
        f.database.space,
        UploadTaskFixture.LOCAL_ONLY);
  }

  private String send(boolean correct) {
    UploadTaskInitRequest request = f.input(3);
    request.setMd5("5289df737df57326fcdd22597afb1fac");
    String id = f.service.initialize(request, 1L).getUploadId();
    f.service.receive(
        id, 0, new MockMultipartFile("file", correct ? new byte[] {1, 2} : new byte[] {8, 9}), 1L);
    f.service.receive(id, 1, new MockMultipartFile("file", new byte[] {3}), 1L);
    return id;
  }

  private void reconciled(long user) {
    long used =
        f.database.jdbc.queryForObject(
            "SELECT COALESCE(SUM(space_bytes),0) FROM r_pan_user_file WHERE user_id=?",
            Long.class,
            user);
    long reserved =
        f.database.jdbc.queryForObject(
            "SELECT COALESCE(SUM(bytes),0) FROM r_pan_upload_reservation WHERE user_id=? AND"
                + " state='RESERVED'",
            Long.class,
            user);
    UserSpaceAccount a = account(user);
    Assert.assertEquals(used, a.getUsedBytes());
    Assert.assertEquals(reserved, a.getReservedBytes());
    Assert.assertTrue(a.getAvailableBytes() >= 0);
  }

  @Test
  public void concurrentReservationsCannotOversell() throws Exception {
    capacity(1010);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    Callable<Boolean> attempt =
        () -> {
          start.await();
          try {
            f.service.initialize(f.input(800), 1L);
            return true;
          } catch (RPanException e) {
            Assert.assertEquals(Integer.valueOf(4101), e.getCode());
            return false;
          }
        };
    try {
      Future<Boolean> a = pool.submit(attempt), b = pool.submit(attempt);
      start.countDown();
      Assert.assertNotEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
      Assert.assertEquals(800, account(1).getReservedBytes());
      reconciled(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void initializationReplayDoesNotReserveTwiceAndRejectsChangedParameters() {
    UploadTaskInitRequest request = f.input(5);
    request.setRequestId(UUID.randomUUID().toString());
    String id = f.service.initialize(request, 1L).getUploadId();
    Assert.assertEquals(id, f.service.initialize(request, 1L).getUploadId());
    Assert.assertEquals(5, account(1).getReservedBytes());
    request.setSize(6L);
    reject(() -> f.service.initialize(request, 1L));
    reconciled(1);
  }

  @Test
  public void taskInsertFailureRollsBackReservationAndAccount() {
    RPanUploadTaskMapper failing = mock(RPanUploadTaskMapper.class);
    doAnswer(
            inv -> {
              f.tasks.insert(inv.getArgument(0));
              throw new IllegalStateException("injected insert failure");
            })
        .when(failing)
        .insert(any());
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
      Assert.fail();
    } catch (IllegalStateException expected) {
    }
    Assert.assertEquals(0, account(1).getReservedBytes());
    reconciled(1);
  }

  @Test
  public void completionCommitsReservationOnceAndCancellationCannotReleaseUsed() {
    String id = send(true);
    Assert.assertEquals(3, account(1).getReservedBytes());
    RPanUploadTask result = completion(f.tasks).complete(id, 1L);
    completion(f.tasks).complete(id, 1L);
    Assert.assertEquals(13, account(1).getUsedBytes());
    Assert.assertEquals(0, account(1).getReservedBytes());
    Assert.assertEquals(
        Long.valueOf(3),
        f.database
            .session
            .getMapper(RPanUserFileMapper.class)
            .selectByPrimaryKey(result.getUserFileId())
            .getSpaceBytes());
    reject(() -> f.service.cancel(id, 1L));
    reconciled(1);
  }

  @Test
  public void registrationRollbackRetainsReservationAndRetrySettlesIt() {
    String id = send(true);
    RPanUploadTaskMapper failing =
        mock(
            RPanUploadTaskMapper.class, inv -> inv.getMethod().invoke(f.tasks, inv.getArguments()));
    doThrow(new IllegalStateException("after inserts and quota settlement"))
        .when(failing)
        .complete(any());
    try {
      completion(failing).complete(id, 1L);
      Assert.fail();
    } catch (IllegalStateException expected) {
    }
    Assert.assertEquals(10, account(1).getUsedBytes());
    Assert.assertEquals(3, account(1).getReservedBytes());
    Assert.assertEquals(UploadTaskState.FINALIZING, f.tasks.selectById(id).getTaskState());
    completion(f.tasks).complete(id, 1L);
    Assert.assertEquals(13, account(1).getUsedBytes());
    reconciled(1);
  }

  @Test
  public void integrityFailureReleasesImmediatelyAndCleanerDoesNotReleaseTwice() {
    String id = send(false);
    reject(() -> completion(f.tasks).complete(id, 1L));
    Assert.assertEquals(UploadTaskState.FAILED, f.tasks.selectById(id).getTaskState());
    Assert.assertEquals(0, account(1).getReservedBytes());
    Assert.assertTrue(cleaner().cleanOne(id));
    reconciled(1);
  }

  @Test
  public void cancelIsIdempotentAndStorageCleanupIsIndependent() {
    String id = send(true);
    reject(() -> f.service.cancel(id, 2L));
    f.service.cancel(id, 1L);
    f.service.cancel(id, 1L);
    Assert.assertTrue(Files.exists(f.part(id, 0)));
    Assert.assertEquals(0, account(1).getReservedBytes());
    Assert.assertTrue(cleaner().cleanOne(id));
    Assert.assertFalse(Files.exists(f.part(id, 0)));
    reconciled(1);
  }

  @Test
  public void expirationReleasesReservationEvenIfBitmapCleanupFails() {
    String id = send(true);
    UploadTaskCleaner cleaner = cleaner();
    cleaner.setClock(Clock.fixed(Instant.now().plus(Duration.ofDays(10)), ZoneOffset.UTC));
    f.progress.unavailable = true;
    Assert.assertFalse(cleaner.cleanOne(id));
    Assert.assertEquals(UploadTaskState.EXPIRED, f.tasks.selectById(id).getTaskState());
    Assert.assertEquals(0, account(1).getReservedBytes());
    f.progress.unavailable = false;
    Assert.assertTrue(cleaner.cleanOne(id));
    reconciled(1);
  }

  @Test
  public void instantAndCopyChargePerLogicalReferenceAndTrashDoesNotRelease() {
    capacity(30);
    work(
        () ->
            Assert.assertTrue(
                f.database.service.secUpload(
                    100L, "instant.txt", FileLifecycleFixture.MD5, 10L, 1L)));
    work(() -> f.database.service.copy("101", 100L, 100L, 1L));
    Assert.assertEquals(30, account(1).getUsedBytes());
    reject(() -> work(() -> f.database.service.copy("101", 100L, 100L, 1L)));
    work(() -> f.database.service.delete(100L, "101", 1L));
    Assert.assertEquals(30, account(1).getUsedBytes());
    work(() -> f.database.service.restoreUserFiles("101", 1L));
    Assert.assertEquals(30, account(1).getUsedBytes());
    work(() -> f.database.service.delete(100L, "101", 1L));
    work(() -> f.database.service.physicalDeleteUserFiles("101", 1L));
    Assert.assertEquals(20, account(1).getUsedBytes());
    Assert.assertEquals("ACTIVE", f.database.state());
    reject(() -> work(() -> f.database.service.physicalDeleteUserFiles("101", 1L)));
    reconciled(1);
  }

  @Test
  public void shareTransferChargesReceiverAndRollsBackWhenInsufficient() {
    f.database.jdbc.update("UPDATE r_pan_user_space SET total_bytes=9 WHERE user_id=2");
    reject(() -> work(() -> f.database.service.saveBatch("101", 200L, 2L)));
    Assert.assertEquals(0, account(2).getUsedBytes());
    f.database.jdbc.update("UPDATE r_pan_user_space SET total_bytes=10 WHERE user_id=2");
    work(() -> f.database.service.saveBatch("101", 200L, 2L));
    Assert.assertEquals(10, account(2).getUsedBytes());
    Assert.assertEquals(10, account(1).getUsedBytes());
    reconciled(1);
    reconciled(2);
  }

  @Test
  public void recursiveCopyCountsRepeatedPhysicalContentAndIsAllOrNothing() {
    f.database.reference(110, 1, 100, 0, 1, 0);
    f.database.jdbc.update("UPDATE r_pan_user_file SET parent_id=110 WHERE file_id=101");
    f.database.reference(111, 1, 110, 900, 0, 0);
    capacity(39);
    reject(() -> work(() -> f.database.service.copy("110", 100L, 100L, 1L)));
    Assert.assertEquals(20, account(1).getUsedBytes());
    reconciled(1);
    capacity(40);
    work(() -> f.database.service.copy("110", 100L, 100L, 1L));
    Assert.assertEquals(40, account(1).getUsedBytes());
    reconciled(1);
  }

  @Test
  public void quotaMutationsRequireBusinessTransaction() {
    try {
      f.database.space.lock(1L);
      Assert.fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test
  public void concurrentInitializationReplayCreatesOneTask() throws Exception {
    UploadTaskInitRequest request = f.input(5);
    request.setRequestId(UUID.randomUUID().toString());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<String> a = pool.submit(() -> f.service.initialize(request, 1L).getUploadId());
      Future<String> b = pool.submit(() -> f.service.initialize(request, 1L).getUploadId());
      Assert.assertEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
      Assert.assertEquals(5, account(1).getReservedBytes());
      reconciled(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void cancellationAndCompletionHaveOneSettlementWinner() throws Exception {
    String id = send(true);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> a =
          pool.submit(
              () -> {
                try {
                  completion(f.tasks).complete(id, 1L);
                } catch (RPanException expected) {
                }
              });
      Future<?> b =
          pool.submit(
              () -> {
                try {
                  f.service.cancel(id, 1L);
                } catch (RPanException expected) {
                }
              });
      a.get(15, TimeUnit.SECONDS);
      b.get(15, TimeUnit.SECONDS);
      UploadTaskState state = f.tasks.selectById(id).getTaskState();
      Assert.assertTrue(state == UploadTaskState.COMPLETED || state == UploadTaskState.EXPIRED);
      Assert.assertEquals(state == UploadTaskState.COMPLETED ? 13 : 10, account(1).getUsedBytes());
      Assert.assertEquals(0, account(1).getReservedBytes());
      reconciled(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void cancellingRetryableFinalizationReleasesAndCleanerDeletesOnlyUnregisteredFile() {
    String id = send(true);
    RPanUploadTaskMapper failing =
        mock(
            RPanUploadTaskMapper.class, inv -> inv.getMethod().invoke(f.tasks, inv.getArguments()));
    doThrow(new IllegalStateException("registration rollback")).when(failing).complete(any());
    try {
      completion(failing).complete(id, 1L);
      Assert.fail();
    } catch (IllegalStateException expected) {
    }
    Path formal = Paths.get(f.tasks.selectById(id).getFinalPath());
    Assert.assertTrue(Files.exists(formal));
    f.service.cancel(id, 1L);
    Assert.assertEquals(0, account(1).getReservedBytes());
    Assert.assertTrue(Files.exists(formal));
    Assert.assertTrue(cleaner().cleanOne(id));
    Assert.assertFalse(Files.exists(formal));
    reconciled(1);
  }
}
