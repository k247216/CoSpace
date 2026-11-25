package com.rubin.rpan.modules.file;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.rubin.rpan.common.exception.RPanException;
import com.rubin.rpan.modules.file.dao.RPanFileMapper;
import com.rubin.rpan.modules.file.dao.RPanUserFileMapper;
import com.rubin.rpan.modules.file.upload.concurrent.UploadLocks;
import com.rubin.rpan.modules.file.upload.model.UploadTaskState;
import com.rubin.rpan.modules.file.upload.service.OssDirectUploads;
import com.rubin.rpan.modules.file.upload.service.UploadCompletionService;
import com.rubin.rpan.modules.file.upload.service.UploadTaskService;
import com.rubin.rpan.storage.ObjectUploadStorageRegistry;
import com.rubin.rpan.storage.oss.OssStorageProcessor;
import com.rubin.rpan.storage.oss.OssStorageProcessor.UploadedPart;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

public class UploadOssDirectTest {
  private static final String MD5 = "900150983cd24fb0d6963f7d28e17f72";
  private static final String LOCATOR = "oss://cospace-test/cospace/files/test-object";
  private static final String SESSION = "test-oss-session";

  @Test
  public void resumesPersistedSessionAndRegistersOnceWhenCompleteResponseIsLost() throws Exception {
    try (var fixture = new UploadTaskFixture(false)) {
      fixture.database.jdbc.execute(
          "ALTER TABLE r_pan_upload_task ADD direct_upload BOOLEAN NOT NULL DEFAULT FALSE");
      fixture.database.jdbc.execute("ALTER TABLE r_pan_upload_task ADD multipart_id VARCHAR(255)");
      fixture.database.jdbc.execute(
          "CREATE TABLE r_pan_upload_part (upload_id VARCHAR(36), part_number INT, "
              + "md5 CHAR(32), PRIMARY KEY (upload_id,part_number))");
      fixture.properties.setOssDirectEnabled(true);
      var oss = mock(OssStorageProcessor.class);
      when(oss.storageType()).thenReturn(OssStorageProcessor.TYPE);
      var registry = new ObjectUploadStorageRegistry(List.of(oss));
      when(oss.locator(anyString())).thenReturn(LOCATOR);
      when(oss.initiateMultipart(eq(LOCATOR), anyString(), eq(MD5))).thenReturn(SESSION);
      when(oss.listMultipartParts(LOCATOR, SESSION)).thenReturn(List.of());
      when(oss.signPart(
              eq(LOCATOR), eq(SESSION), eq(1), eq(3L), eq("kAFQmDzST7DWlj99KOF/cg=="), any()))
          .thenReturn("https://test.invalid");

      var input = fixture.input(3);
      long usedBefore = fixture.database.space.view(1L).getUsedBytes();
      input.setMd5(MD5);
      input.setDirectUpload(true);
      input.setRequestId(UUID.randomUUID().toString());
      var first = taskService(fixture, registry, new UploadLocks());
      var task = first.initialize(input, 1L);
      assertTrue(task.isDirectUpload());
      assertEquals(OssDirectUploads.PART_SIZE, task.getChunkSize().intValue());
      assertEquals(task.getUploadId(), first.initialize(input, 1L).getUploadId());
      assertEquals(3L, fixture.database.space.view(1L).getReservedBytes());
      assertEquals("https://test.invalid", first.signPart(task.getUploadId(), 0, MD5, 1L).url());
      assertThrows(
          RPanException.class,
          () -> first.signPart(task.getUploadId(), 0, "00000000000000000000000000000000", 1L));

      var locks = new UploadLocks();
      var resumed = taskService(fixture, registry, locks);
      var parts = List.of(new UploadedPart(1, 3, MD5.toUpperCase(java.util.Locale.ROOT)));
      when(oss.listMultipartParts(LOCATOR, SESSION)).thenReturn(parts);
      assertEquals(List.of(0), resumed.status(task.getUploadId(), 1L).getUploadedChunks());
      verify(oss, times(1)).initiateMultipart(eq(LOCATOR), anyString(), eq(MD5));

      var uploaded = new AtomicBoolean();
      when(oss.multipartObjectSize(LOCATOR, task.getUploadId(), MD5))
          .thenAnswer(call -> uploaded.get() ? 3L : null);
      doAnswer(
              call -> {
                uploaded.set(true);
                throw new IOException("OSS completed the object but its response was lost");
              })
          .when(oss)
          .completeMultipart(LOCATOR, SESSION, parts);
      doAnswer(
              call -> {
                call.<OutputStream>getArgument(1).write("abc".getBytes(StandardCharsets.US_ASCII));
                return null;
              })
          .when(oss)
          .read2OutputStream(eq(LOCATOR), any(OutputStream.class));

      var completion =
          new UploadCompletionService(
              fixture.tasks,
              fixture.database.session.getMapper(RPanFileMapper.class),
              fixture.database.session.getMapper(RPanUserFileMapper.class),
              fixture.storage,
              fixture.progress,
              locks,
              new DataSourceTransactionManager(fixture.database.dataSource),
              fixture.root.toString(),
              fixture.database.space,
              registry);
      assertThrows(RPanException.class, () -> completion.complete(task.getUploadId(), 1L));
      assertEquals(UploadTaskState.FINALIZING, resumed.status(task.getUploadId(), 1L).getState());
      assertEquals(3L, fixture.database.space.view(1L).getReservedBytes());
      var done = completion.complete(task.getUploadId(), 1L);
      assertEquals(UploadTaskState.COMPLETED, done.getTaskState());
      assertEquals(
          done.getUserFileId(), completion.complete(task.getUploadId(), 1L).getUserFileId());
      assertEquals(usedBefore + 3L, fixture.database.space.view(1L).getUsedBytes());
      assertEquals(0L, fixture.database.space.view(1L).getReservedBytes());
      verify(oss, times(1)).completeMultipart(LOCATOR, SESSION, parts);
    }
  }

  private UploadTaskService taskService(
      UploadTaskFixture fixture, ObjectUploadStorageRegistry registry, UploadLocks locks) {
    return new UploadTaskService(
        fixture.tasks,
        fixture.database.session.getMapper(RPanUserFileMapper.class),
        fixture.progress,
        fixture.storage,
        locks,
        fixture.properties,
        new DataSourceTransactionManager(fixture.database.dataSource),
        OssStorageProcessor.TYPE,
        fixture.database.space,
        fixture.limiter,
        registry);
  }
}
