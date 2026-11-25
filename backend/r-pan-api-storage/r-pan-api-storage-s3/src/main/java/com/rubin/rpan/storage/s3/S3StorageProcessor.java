package com.rubin.rpan.storage.s3;

import com.rubin.rpan.storage.ObjectUploadStorage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

public final class S3StorageProcessor implements ObjectUploadStorage {
  public static final String TYPE = "com.rubin.rpan.storage.s3.S3StorageProcessor";
  private static final String PREFIX = "cospace/files/";
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
  private final S3Client client;
  private final String bucket;

  public S3StorageProcessor(S3Client client, String bucket) {
    if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
      throw new IllegalArgumentException("Invalid S3 bucket name");
    }
    this.client = client;
    this.bucket = bucket;
  }

  @Override
  public String storageType() {
    return TYPE;
  }

  @Override
  public String locator(String uploadId) {
    if (!UUID_PATTERN.matcher(uploadId).matches()) {
      throw new IllegalArgumentException("Invalid object identifier");
    }
    return "s3://" + bucket + "/" + PREFIX + uploadId;
  }

  @Override
  public boolean matchesPublishedObject(String locator, long size, String md5) throws IOException {
    HeadObjectResponse object;
    String key = objectKey(locator);
    try {
      object = client.headObject(request -> request.bucket(bucket).key(key));
    } catch (S3Exception e) {
      if (e.statusCode() == 404) return false;
      throw new IOException("Could not inspect S3 object", e);
    } catch (SdkException e) {
      throw new IOException("Could not inspect S3 object", e);
    }
    if (object.contentLength() != size || !md5.equals(object.metadata().get("cospace-md5"))) {
      throw new IOException("Existing S3 object does not match the upload");
    }
    return true;
  }

  @Override
  public void publish(String locator, Path verifiedFile, long size, String md5) throws IOException {
    String key = objectKey(locator);
    String contentMd5 = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(md5));
    try {
      client.putObject(
          request ->
              request
                  .bucket(bucket)
                  .key(key)
                  .ifNoneMatch("*")
                  .contentLength(size)
                  .contentMD5(contentMd5)
                  .metadata(Map.of("cospace-md5", md5)),
          RequestBody.fromFile(verifiedFile));
    } catch (S3Exception e) {
      if (e.statusCode() == 412 && matchesPublishedObject(locator, size, md5)) return;
      throw new IOException("Could not publish S3 object", e);
    } catch (SdkException e) {
      throw new IOException("Could not publish S3 object", e);
    }
  }

  @Override
  public String store(InputStream inputStream, Long size, String name) throws IOException {
    String locator = locator(UUID.randomUUID().toString());
    String key = objectKey(locator);
    try {
      client.putObject(
          request -> request.bucket(bucket).key(key).ifNoneMatch("*").contentLength(size),
          RequestBody.fromInputStream(inputStream, size));
    } catch (SdkException e) {
      throw new IOException("Could not store S3 object", e);
    }
    return locator;
  }

  @Override
  public void read2OutputStream(String locator, OutputStream outputStream) throws IOException {
    String key = objectKey(locator);
    try (InputStream content = client.getObject(request -> request.bucket(bucket).key(key))) {
      content.transferTo(outputStream);
    } catch (SdkException e) {
      throw new IOException("Could not read S3 object", e);
    }
  }

  @Override
  public void delete(String locator) throws IOException {
    String key = objectKey(locator);
    try {
      client.deleteObject(request -> request.bucket(bucket).key(key));
    } catch (SdkException e) {
      throw new IOException("Could not delete S3 object", e);
    }
  }

  @Override
  public boolean ownsLocator(String locator) {
    String prefix = "s3://" + bucket + "/" + PREFIX;
    return locator != null
        && locator.startsWith(prefix)
        && UUID_PATTERN.matcher(locator.substring(prefix.length())).matches();
  }

  private String objectKey(String locator) throws IOException {
    if (!ownsLocator(locator)) {
      throw new IOException("S3 locator is outside the configured bucket or object prefix");
    }
    return locator.substring(("s3://" + bucket + "/").length());
  }
}
