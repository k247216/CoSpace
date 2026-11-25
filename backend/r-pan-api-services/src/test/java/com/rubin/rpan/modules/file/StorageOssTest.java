package com.rubin.rpan.modules.file;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.CompleteMultipartUploadRequest;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.rubin.rpan.modules.file.cleanup.storage.CleanupStorageRouter;
import com.rubin.rpan.modules.file.cleanup.storage.LocalCleanupStorageDeleter;
import com.rubin.rpan.modules.file.cleanup.storage.UnsafeCleanupException;
import com.rubin.rpan.modules.file.entity.RPanFileCleanupTask;
import com.rubin.rpan.storage.ObjectUploadStorageRegistry;
import com.rubin.rpan.storage.oss.OssClientConfig;
import com.rubin.rpan.storage.oss.OssStorageProcessor;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

public class StorageOssTest {
  private static final String BUCKET = "cospace-private";
  private static final String ID = "d157de86-df42-43dd-bf12-f0462c3c1203";
  private static final String KEY = "cospace/files/" + ID;
  private static final String MD5 = "900150983cd24fb0d6963f7d28e17f72";
  private final OSS client = mock(OSS.class);
  private final OssStorageProcessor storage = new OssStorageProcessor(client, BUCKET);

  @Test
  public void multipartSignatureBindsTheTaskPartAndBody() throws Exception {
    when(client.generatePresignedUrl(any(GeneratePresignedUrlRequest.class)))
        .thenReturn(java.net.URI.create("https://test.invalid").toURL());
    storage.signPart(
        storage.locator(ID),
        "session",
        2,
        3,
        "kAFQmDzST7DWlj99KOF/cg==",
        Instant.parse("2026-11-23T12:00:00Z"));
    var request = ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
    verify(client).generatePresignedUrl(request.capture());
    var signed = request.getValue();
    assertEquals(KEY, signed.getKey());
    assertEquals("2", signed.getQueryParameter().get("partNumber"));
    assertEquals("session", signed.getQueryParameter().get("uploadId"));
    assertEquals("3", signed.getHeaders().get("content-length"));
    assertTrue(signed.getAdditionalHeaderNames().contains("content-length"));
    assertEquals("kAFQmDzST7DWlj99KOF/cg==", signed.getContentMD5());
  }

  @Test
  public void completablePartsAreMutableForSdkSorting() throws IOException {
    when(client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
        .thenAnswer(
            call -> {
              var request = call.<CompleteMultipartUploadRequest>getArgument(0);
              request.getPartETags().sort(Comparator.comparingInt(part -> part.getPartNumber()));
              assertEquals(1, request.getPartETags().getFirst().getPartNumber());
              return null;
            });
    storage.completeMultipart(
        storage.locator(ID),
        "session",
        List.of(
            new OssStorageProcessor.UploadedPart(2, 3, MD5),
            new OssStorageProcessor.UploadedPart(1, 3, MD5)));
  }

  @Test
  public void enabledOssCoexistsWithLocalAsDefault() {
    new ApplicationContextRunner()
        .withUserConfiguration(OssClientConfig.class, ObjectUploadStorageRegistry.class)
        .withPropertyValues(
            "rpan.storage.oss.enabled=true",
            "rpan.storage.oss.region=cn-hangzhou",
            "rpan.storage.oss.bucket-name=" + BUCKET,
            "rpan.storage.oss.access-key-id=test-id",
            "rpan.storage.oss.access-key-secret=test-secret",
            "rpan.storage.processor.type=com.rubin.rpan.storage.local.LocalStorageProcessor")
        .run(
            context ->
                assertSame(
                    context.getBean(OssStorageProcessor.class),
                    context
                        .getBean(ObjectUploadStorageRegistry.class)
                        .find(OssStorageProcessor.TYPE)));
  }

  @Test
  public void duplicatePutReusesOnlyTheExpectedObject() throws IOException {
    when(client.putObject(any(PutObjectRequest.class)))
        .thenThrow(
            new OSSException(
                "exists", "FileAlreadyExists", "request-id", "test-endpoint", null, null, null));
    ObjectMetadata stored = new ObjectMetadata();
    stored.setContentLength(3);
    stored.addUserMetadata("cospace-md5", MD5);
    when(client.getObjectMetadata(BUCKET, KEY)).thenReturn(stored);

    storage.publish(storage.locator(ID), Path.of("verified-file"), 3, MD5);

    ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(client).putObject(request.capture());
    assertEquals(KEY, request.getValue().getKey());
    assertEquals(
        "true", request.getValue().getMetadata().getRawMetadata().get("x-oss-forbid-overwrite"));
    assertEquals("kAFQmDzST7DWlj99KOF/cg==", request.getValue().getMetadata().getContentMD5());
    stored.setContentLength(4);
    assertThrows(
        IOException.class,
        () -> storage.publish(storage.locator(ID), Path.of("verified-file"), 3, MD5));
  }

  @Test
  public void deniedHeadIsNotMistakenForAnAbsentObject() throws IOException {
    when(client.getObjectMetadata(BUCKET, KEY))
        .thenThrow(
            new OSSException("absent", "NoSuchKey", "req1", "test-endpoint", null, null, null),
            new OSSException("denied", "AccessDenied", "req2", "test-endpoint", null, null, null));
    assertFalse(storage.matchesPublishedObject(storage.locator(ID), 3, MD5));
    IOException failure =
        assertThrows(
            IOException.class, () -> storage.matchesPublishedObject(storage.locator(ID), 3, MD5));
    assertTrue(failure.getMessage().contains("AccessDenied"));
    assertTrue(failure.getMessage().contains("req2"));
  }

  @Test
  public void cleanupRejectsAMismatchedBucketBeforeCallingOss() throws IOException {
    var router =
        new CleanupStorageRouter(
            mock(LocalCleanupStorageDeleter.class),
            new ObjectUploadStorageRegistry(List.of(storage)));
    RPanFileCleanupTask task = new RPanFileCleanupTask();
    task.setStorageType(OssStorageProcessor.TYPE);
    task.setStorageLocator("oss://another-bucket/" + KEY);
    assertThrows(UnsafeCleanupException.class, () -> router.delete(task));
    verifyNoInteractions(client);

    task.setStorageLocator(storage.locator(ID));
    router.delete(task);
    verify(client).deleteObject(BUCKET, KEY);
  }
}
