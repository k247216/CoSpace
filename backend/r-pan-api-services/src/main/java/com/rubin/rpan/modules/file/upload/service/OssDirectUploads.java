package com.rubin.rpan.modules.file.upload.service;

import com.rubin.rpan.common.exception.RPanException;
import com.rubin.rpan.modules.file.dao.RPanUploadTaskMapper;
import com.rubin.rpan.modules.file.entity.RPanUploadTask;
import com.rubin.rpan.modules.file.upload.model.UploadPartDigest;
import com.rubin.rpan.modules.file.upload.storage.UploadChunkStorage;
import com.rubin.rpan.modules.file.upload.storage.UploadChunkStorage.IntegrityException;
import com.rubin.rpan.storage.ObjectUploadStorageRegistry;
import com.rubin.rpan.storage.oss.OssStorageProcessor;
import com.rubin.rpan.storage.oss.OssStorageProcessor.UploadedPart;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class OssDirectUploads {
  public static final int PART_SIZE = 5 * 1024 * 1024;
  private static final int SIGNATURE_SECONDS = 300;

  private final RPanUploadTaskMapper tasks;
  private final ObjectUploadStorageRegistry storage;

  public OssDirectUploads(RPanUploadTaskMapper tasks, ObjectUploadStorageRegistry storage) {
    this.tasks = tasks;
    this.storage = storage;
  }

  public boolean supports(String type) {
    return storage.find(type) instanceof OssStorageProcessor;
  }

  public void initialize(RPanUploadTask task) throws IOException {
    if (task.getMultipartId() != null) return;
    var backend = backend(task);
    String locator = backend.locator(task.getUploadId());
    String multipartId =
        backend.initiateMultipart(locator, task.getUploadId(), task.getExpectedMd5());
    // A database error may have an ambiguous commit outcome. Bucket lifecycle handles any untracked
    // session.
    if (tasks.setMultipartId(task.getUploadId(), multipartId, locator) != 1) {
      backend.abortMultipart(locator, multipartId);
      throw new IOException("Could not record OSS multipart ID");
    }
    task.setMultipartId(multipartId);
    task.setFinalPath(locator);
  }

  public record SignedPart(String url, Map<String, String> headers, Instant expiresAt) {}

  public SignedPart sign(RPanUploadTask task, int index, String digest, Instant now)
      throws IOException {
    long size = UploadChunkStorage.expectedSize(task, index);
    String md5 = digest.toLowerCase(Locale.ROOT);
    initialize(task);
    String previous = tasks.selectPartDigest(task.getUploadId(), index + 1);
    if (previous == null) {
      if (tasks.insertPartDigest(task.getUploadId(), index + 1, md5) != 1) {
        throw new IOException("Could not record signed part digest");
      }
    } else if (!md5.equals(previous)) {
      throw new RPanException("同一任务的分片内容不能改变");
    }
    Instant expiresAt = task.getExpiresAt().toInstant();
    Instant limit = now.plusSeconds(SIGNATURE_SECONDS);
    if (expiresAt.isAfter(limit)) expiresAt = limit;
    String contentMd5 = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(md5));
    var backend = backend(task);
    String url =
        backend.signPart(
            backend.locator(task.getUploadId()),
            task.getMultipartId(),
            index + 1,
            size,
            contentMd5,
            expiresAt);
    return new SignedPart(
        url,
        Map.of("Content-MD5", contentMd5, "Content-Type", OssStorageProcessor.PART_CONTENT_TYPE),
        expiresAt);
  }

  public List<UploadedPart> parts(RPanUploadTask task) throws IOException {
    if (task.getMultipartId() == null) return List.of();
    var backend = backend(task);
    var expected =
        tasks.listPartDigests(task.getUploadId()).stream()
            .collect(Collectors.toMap(UploadPartDigest::part, Function.identity()));
    return backend
        .listMultipartParts(backend.locator(task.getUploadId()), task.getMultipartId())
        .stream()
        .filter(part -> part.number() > 0 && part.number() <= task.getTotalChunks())
        .filter(part -> part.size() == UploadChunkStorage.expectedSize(task, part.number() - 1))
        .filter(
            part ->
                expected.containsKey(part.number())
                    && expected
                        .get(part.number())
                        .md5()
                        .equalsIgnoreCase(part.etag().replace("\"", "")))
        .sorted(Comparator.comparingInt(UploadedPart::number))
        .toList();
  }

  public void complete(RPanUploadTask task) throws IOException {
    var backend = backend(task);
    String locator = task.getFinalPath();
    Long size = backend.multipartObjectSize(locator, task.getUploadId(), task.getExpectedMd5());
    if (size == null) {
      List<UploadedPart> parts = parts(task);
      if (parts.size() != task.getTotalChunks())
        throw new IOException("OSS multipart parts do not match");
      backend.completeMultipart(locator, task.getMultipartId(), parts);
      size = backend.multipartObjectSize(locator, task.getUploadId(), task.getExpectedMd5());
    }
    if (size == null) throw new IOException("OSS object has not become visible");
    if (!size.equals(task.getFileSize())) throw new IntegrityException("整文件大小校验失败");
    var digest = md5();
    try (var output = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
      // The completed object's multipart ETag is not a whole-file MD5.
      backend.read2OutputStream(locator, output);
    }
    if (!task.getExpectedMd5().equals(HexFormat.of().formatHex(digest.digest()))) {
      throw new IntegrityException("整文件校验失败");
    }
  }

  public void clean(RPanUploadTask task, boolean abandoned) throws IOException {
    var backend = backend(task);
    String locator = backend.locator(task.getUploadId());
    if (abandoned) {
      if (task.getMultipartId() != null) backend.abortMultipart(locator, task.getMultipartId());
      if (task.getFinalPath() != null
          && backend.multipartObjectSize(locator, task.getUploadId(), task.getExpectedMd5())
              != null) {
        backend.delete(locator);
      }
    }
    tasks.deletePartDigests(task.getUploadId());
  }

  private OssStorageProcessor backend(RPanUploadTask task) {
    if (storage.find(task.getStorageType()) instanceof OssStorageProcessor oss) return oss;
    throw new RPanException("上传任务的 OSS 直传存储未配置");
  }

  private static MessageDigest md5() {
    try {
      return MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }
}
