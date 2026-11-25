package com.rubin.rpan.modules.file;

import com.rubin.rpan.common.exception.RPanException;
import com.rubin.rpan.modules.file.constant.CleanupDeliveryState;
import com.rubin.rpan.modules.file.constant.CleanupExecutionState;
import com.rubin.rpan.modules.file.dao.RPanFileCleanupTaskMapper;
import com.rubin.rpan.modules.file.entity.RPanFileCleanupTask;
import java.nio.file.*;
import java.util.Collections;
import org.junit.*;

public class FileLifecycleTransactionTest {
  private FileLifecycleFixture f;

  @Before
  public void setUp() throws Exception {
    f = new FileLifecycleFixture(false);
  }

  @After
  public void tearDown() {
    if (f != null) f.close();
  }

  @Test
  public void lastReferenceCreatesTaskWithoutDeletingContent() throws Exception {
    Path content = Files.createTempFile("rpan-cleanup-", ".txt");
    try {
      Files.write(content, new byte[] {1, 2, 3});
      f.jdbc.update("UPDATE r_pan_file SET real_path=? WHERE file_id=900", content.toString());
      f.jdbc.update("UPDATE r_pan_user_file SET del_flag=1 WHERE file_id=101");
      f.tx.execute(
          status -> {
            f.service.physicalDeleteUserFiles("101", 1L);
            return null;
          });
      Assert.assertEquals("DELETING", f.state());
      Assert.assertEquals(1, f.tasks());
      Assert.assertEquals(
          0,
          f.jdbc
              .queryForObject(
                  "SELECT COUNT(*) FROM r_pan_user_file WHERE file_id=101", Integer.class)
              .intValue());
      Assert.assertTrue(Files.exists(content));
      RPanFileCleanupTask task =
          f.session.getMapper(RPanFileCleanupTaskMapper.class).selectByRealFileId(900L);
      Assert.assertEquals(content.toString(), task.getStorageLocator());
      Assert.assertEquals(
          "com.rubin.rpan.storage.local.LocalStorageProcessor", task.getStorageType());
      Assert.assertEquals(CleanupExecutionState.PENDING, task.getExecutionState());
      Assert.assertEquals(CleanupDeliveryState.PENDING, task.getDeliveryState());
    } finally {
      Files.deleteIfExists(content);
    }
  }

  @Test
  public void cleanupUsesTheFilesBackendInsteadOfTheLegacyBackend() {
    f.jdbc.update("UPDATE r_pan_file SET storage_type='other-backend' WHERE file_id=900");
    f.jdbc.update("DELETE FROM r_pan_user_file WHERE file_id=101");
    f.tx.executeWithoutResult(
        status -> f.lifecycle.scheduleIfUnreferenced(Collections.singleton(900L)));
    RPanFileCleanupTask task =
        f.session.getMapper(RPanFileCleanupTaskMapper.class).selectByRealFileId(900L);
    Assert.assertEquals("other-backend", task.getStorageType());
    Assert.assertEquals("/unused", task.getStorageLocator());
  }

  @Test
  public void anotherUsersRecycledReferenceStillProtectsContent() {
    f.reference(201, 2, 200, 900, 0, 1);
    f.jdbc.update("UPDATE r_pan_user_file SET del_flag=1 WHERE file_id=101");
    f.tx.execute(
        status -> {
          f.service.physicalDeleteUserFiles("101", 1L);
          return null;
        });
    Assert.assertEquals("ACTIVE", f.state());
    Assert.assertEquals(0, f.tasks());
  }

  @Test
  public void rollbackRestoresReferenceStateAndRemovesTask() {
    f.jdbc.update("UPDATE r_pan_user_file SET del_flag=1 WHERE file_id=101");
    f.tx.execute(
        status -> {
          f.service.physicalDeleteUserFiles("101", 1L);
          status.setRollbackOnly();
          return null;
        });
    Assert.assertEquals("ACTIVE", f.state());
    Assert.assertEquals(0, f.tasks());
    Assert.assertEquals(
        1,
        f.jdbc
            .queryForObject("SELECT COUNT(*) FROM r_pan_user_file WHERE file_id=101", Integer.class)
            .intValue());
  }

  @Test
  public void taskInsertFailureRollsBackReferenceAndState() {
    f.jdbc.update("UPDATE r_pan_user_file SET del_flag=1 WHERE file_id=101");
    f.jdbc.update(
        "INSERT INTO r_pan_file_cleanup_task(task_id,real_file_id,storage_type,storage_locator)"
            + " VALUES(555,900,'test','/unused')");
    try {
      f.tx.execute(
          status -> {
            f.service.physicalDeleteUserFiles("101", 1L);
            return null;
          });
      Assert.fail("Expected duplicate task failure");
    } catch (org.springframework.dao.DataIntegrityViolationException expected) {
    }
    Assert.assertEquals("ACTIVE", f.state());
    Assert.assertEquals(1, f.tasks());
    Assert.assertEquals(
        1,
        f.jdbc
            .queryForObject("SELECT COUNT(*) FROM r_pan_user_file WHERE file_id=101", Integer.class)
            .intValue());
  }

  @Test
  public void physicalDeleteRejectsOtherUserAndNonRecycledSource() {
    reject(() -> f.service.physicalDeleteUserFiles("101", 2L));
    reject(() -> f.service.physicalDeleteUserFiles("101", 1L));
    Assert.assertEquals("ACTIVE", f.state());
    Assert.assertEquals(0, f.tasks());
  }

  @Test
  public void deletingFileCannotBeReusedOrCopiedOrTransferredFromShare() {
    f.jdbc.update("UPDATE r_pan_file SET lifecycle_state='DELETING' WHERE file_id=900");
    Assert.assertFalse(
        f.tx.execute(
            status -> f.service.secUpload(100L, "copy.txt", FileLifecycleFixture.MD5, 10L, 1L)));
    reject(() -> f.service.copy("101", 100L, 100L, 1L));
    reject(() -> f.service.saveBatch("101", 200L, 2L));
  }

  @Test
  public void copyAndShareTransferCreateReferencesWhileActive() {
    f.tx.execute(
        status -> {
          f.service.copy("101", 100L, 100L, 1L);
          return null;
        });
    f.tx.execute(
        status -> {
          f.service.saveBatch("101", 200L, 2L);
          return null;
        });
    Assert.assertEquals(
        3,
        f.jdbc
            .queryForObject(
                "SELECT COUNT(*) FROM r_pan_user_file WHERE real_file_id=900", Integer.class)
            .intValue());
    Assert.assertEquals("ACTIVE", f.state());
    Assert.assertEquals(0, f.tasks());
  }

  @Test
  public void copyCannotReadAnotherUsersSource() {
    reject(() -> f.service.copy("101", 200L, 200L, 2L));
  }

  @Test
  public void lifecycleRejectsCallsWithoutTransaction() {
    try {
      f.lifecycle.lockActiveFiles(Collections.singleton(900L));
      Assert.fail("Expected transaction guard");
    } catch (IllegalStateException expected) {
    }
  }

  @Test
  public void repeatedSchedulingDoesNotDuplicateTask() {
    f.jdbc.update("DELETE FROM r_pan_user_file WHERE file_id=101");
    f.tx.execute(
        status -> {
          f.lifecycle.scheduleIfUnreferenced(Collections.singleton(900L));
          return null;
        });
    f.tx.execute(
        status -> {
          f.lifecycle.scheduleIfUnreferenced(Collections.singleton(900L));
          return null;
        });
    Assert.assertEquals(1, f.tasks());
  }

  @Test
  public void recycledFolderSchedulesAllUnreferencedChildContent() {
    f.reference(110, 1, 100, 0, 1, 1);
    f.jdbc.update("UPDATE r_pan_user_file SET parent_id=110 WHERE file_id=101");
    f.jdbc.update(
        "INSERT INTO r_pan_file(file_id,filename,real_path,file_size,md5,create_user,create_time)"
            + " VALUES(901,'second','/unused/second','3',?,1,CURRENT_TIMESTAMP)",
        FileLifecycleFixture.MD5);
    f.reference(111, 1, 110, 901, 0, 0);
    f.tx.execute(
        status -> {
          f.service.physicalDeleteUserFiles("110", 1L);
          return null;
        });
    Assert.assertEquals(2, f.tasks());
    Assert.assertEquals(
        2,
        f.jdbc
            .queryForObject(
                "SELECT COUNT(*) FROM r_pan_file WHERE lifecycle_state='DELETING'", Integer.class)
            .intValue());
    Assert.assertEquals(
        0,
        f.jdbc
            .queryForObject(
                "SELECT COUNT(*) FROM r_pan_user_file WHERE file_id IN (101,110,111)",
                Integer.class)
            .intValue());
  }

  private void reject(Runnable work) {
    try {
      f.tx.execute(
          status -> {
            work.run();
            return null;
          });
      Assert.fail("Expected rejection");
    } catch (RPanException expected) {
    }
  }
}
