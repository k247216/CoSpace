package com.rubin.rpan.modules.file;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.rubin.rpan.common.exception.RPanException;
import com.rubin.rpan.modules.file.dao.*;
import com.rubin.rpan.modules.file.entity.RPanUploadTask;
import com.rubin.rpan.modules.file.upload.model.UploadTaskInitRequest;
import com.rubin.rpan.modules.file.upload.model.UploadTaskState;
import com.rubin.rpan.modules.file.upload.service.UploadCompletionService;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;

public class UploadCompletionTest {
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

  private UploadCompletionService completion(RPanUploadTaskMapper tasks) {
    return new UploadCompletionService(
        tasks,
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

  private String init(byte[] bytes) throws Exception {
    UploadTaskInitRequest input = f.input(bytes.length);
    StringBuilder md5 = new StringBuilder();
    for (byte b : MessageDigest.getInstance("MD5").digest(bytes))
      md5.append(String.format("%02x", b & 255));
    input.setMd5(md5.toString());
    return f.service.initialize(input, 1L).getUploadId();
  }

  private void send(String id, byte[] bytes) {
    int count = Math.max(1, (bytes.length + 1) / 2);
    for (int index = count - 1; index >= 0; index--)
      f.service.receive(
          id,
          index,
          new MockMultipartFile(
              "file", Arrays.copyOfRange(bytes, index * 2, Math.min(bytes.length, index * 2 + 2))),
          1L);
  }

  private void reject(Runnable action) {
    try {
      action.run();
      Assert.fail("Expected rejection");
    } catch (RPanException expected) {
    }
  }

  private int count(String table) {
    return f.database.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }

  @Test
  public void outOfOrderChunksMergeValidateAndCommitOnce() throws Exception {
    byte[] bytes = {1, 2, 3, 4, 5};
    String id = init(bytes);
    send(id, bytes);
    RPanUploadTask done = completion(f.tasks).complete(id, 1L);
    Assert.assertEquals(UploadTaskState.COMPLETED, done.getTaskState());
    Assert.assertArrayEquals(bytes, Files.readAllBytes(Paths.get(done.getFinalPath())));
    Assert.assertEquals(
        done.getRealFileId(),
        f.database
            .session
            .getMapper(RPanUserFileMapper.class)
            .selectByPrimaryKey(done.getUserFileId())
            .getRealFileId());
    Assert.assertEquals(
        com.rubin.rpan.modules.file.constant.FileLifecycleState.ACTIVE,
        f.database
            .session
            .getMapper(RPanFileMapper.class)
            .selectByPrimaryKey(done.getRealFileId())
            .getLifecycleState());
    Assert.assertEquals(2, count("r_pan_file"));
    Assert.assertEquals(4, count("r_pan_user_file"));
    Assert.assertFalse(Files.exists(f.part(id, 0)));
    Assert.assertNull(f.progress.read(id));
    Assert.assertEquals(done.getUserFileId(), completion(f.tasks).complete(id, 1L).getUserFileId());
    Assert.assertEquals(2, count("r_pan_file"));
    Assert.assertEquals(4, count("r_pan_user_file"));
    Assert.assertEquals(done.getUserFileId(), f.service.status(id, 1L).getUserFileId());
    Assert.assertTrue(f.service.status(id, 1L).getAllChunksReceived());
  }

  @Test
  public void missingChunkDoesNotEnterFinalizing() throws Exception {
    String id = init(new byte[] {1, 2, 3});
    f.service.receive(id, 1, new MockMultipartFile("file", new byte[] {3}), 1L);
    reject(() -> completion(f.tasks).complete(id, 1L));
    Assert.assertEquals(UploadTaskState.UPLOADING, f.tasks.selectById(id).getTaskState());
    Assert.assertEquals(1, count("r_pan_file"));
  }

  @Test
  public void wrongWholeFileDigestFailsBeforePublishingMetadata() throws Exception {
    String id = init(new byte[] {1, 2, 3});
    send(id, new byte[] {8, 9, 0});
    reject(() -> completion(f.tasks).complete(id, 1L));
    RPanUploadTask failed = f.tasks.selectById(id);
    Assert.assertEquals(UploadTaskState.FAILED, failed.getTaskState());
    Assert.assertTrue(failed.getLastError().contains("MD5"));
    Assert.assertFalse(Files.exists(Paths.get(failed.getFinalPath())));
    Assert.assertEquals(1, count("r_pan_file"));
    Assert.assertEquals(3, count("r_pan_user_file"));
  }

  @Test
  public void dbRollbackKeepsFinalFileAndRetryReusesIt() throws Exception {
    byte[] bytes = {1, 2, 3};
    String id = init(bytes);
    send(id, bytes);
    RPanUploadTaskMapper failing =
        mock(
            RPanUploadTaskMapper.class,
            invocation -> invocation.getMethod().invoke(f.tasks, invocation.getArguments()));
    doThrow(new IllegalStateException("Injected failure after both inserts"))
        .when(failing)
        .complete(any());
    try {
      completion(failing).complete(id, 1L);
      Assert.fail("Expected transaction rollback");
    } catch (IllegalStateException expected) {
    }
    RPanUploadTask interrupted = f.tasks.selectById(id);
    Assert.assertEquals(UploadTaskState.FINALIZING, interrupted.getTaskState());
    Assert.assertArrayEquals(bytes, Files.readAllBytes(Paths.get(interrupted.getFinalPath())));
    Assert.assertEquals(1, count("r_pan_file"));
    Assert.assertEquals(3, count("r_pan_user_file"));
    // 即使分片不再可用，重启后的完成请求也能校验并复用已经发布的完整文件。
    Files.delete(f.part(id, 0));
    RPanUploadTask done = completion(f.tasks).complete(id, 1L);
    Assert.assertEquals(UploadTaskState.COMPLETED, done.getTaskState());
    Assert.assertEquals(2, count("r_pan_file"));
    Assert.assertEquals(4, count("r_pan_user_file"));
  }

  @Test
  public void storageFailureRemainsRetryable() throws Exception {
    String id = init(new byte[] {1, 2, 3});
    send(id, new byte[] {1, 2, 3});
    // 先持久化收尾意图，模拟进程在合并前退出，再制造缺片IO错误。
    f.database.jdbc.update(
        "UPDATE r_pan_upload_task SET task_state='FINALIZING',final_path=? WHERE upload_id=?",
        f.root.toRealPath().resolve("final/uploaded").resolve(id).toString(),
        id);
    Files.delete(f.part(id, 0));
    reject(() -> completion(f.tasks).complete(id, 1L));
    Assert.assertEquals(UploadTaskState.FINALIZING, f.tasks.selectById(id).getTaskState());
    Files.write(f.part(id, 0), new byte[] {1, 2});
    Assert.assertEquals(
        UploadTaskState.COMPLETED, completion(f.tasks).complete(id, 1L).getTaskState());
  }

  @Test
  public void concurrentCompletionCreatesOneReference() throws Exception {
    String id = init(new byte[] {1, 2, 3});
    send(id, new byte[] {1, 2, 3});
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<RPanUploadTask> a = pool.submit(() -> completion(f.tasks).complete(id, 1L));
      Future<RPanUploadTask> b = pool.submit(() -> completion(f.tasks).complete(id, 1L));
      Assert.assertEquals(
          a.get(15, TimeUnit.SECONDS).getUserFileId(), b.get(15, TimeUnit.SECONDS).getUserFileId());
      Assert.assertEquals(2, count("r_pan_file"));
      Assert.assertEquals(4, count("r_pan_user_file"));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void ownershipAndRemovedParentBlockCompletion() throws Exception {
    String id = init(new byte[] {1, 2});
    send(id, new byte[] {1, 2});
    reject(() -> completion(f.tasks).complete(id, 2L));
    f.database.jdbc.update("UPDATE r_pan_user_file SET del_flag=1 WHERE file_id=100");
    reject(() -> completion(f.tasks).complete(id, 1L));
    Assert.assertEquals(1, count("r_pan_file"));
    Assert.assertEquals(3, count("r_pan_user_file"));
    Assert.assertEquals(UploadTaskState.FINALIZING, f.tasks.selectById(id).getTaskState());
  }

  @Test
  public void zeroByteFileCompletesWithoutRedisDependency() throws Exception {
    String id = init(new byte[0]);
    send(id, new byte[0]);
    f.progress.unavailable = true;
    RPanUploadTask done = completion(f.tasks).complete(id, 1L);
    Assert.assertEquals(0, Files.size(Paths.get(done.getFinalPath())));
    Assert.assertEquals(UploadTaskState.COMPLETED, f.service.status(id, 1L).getState());
  }
}
