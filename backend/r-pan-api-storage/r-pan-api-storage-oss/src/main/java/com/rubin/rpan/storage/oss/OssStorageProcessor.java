package com.rubin.rpan.storage.oss;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.AbortMultipartUploadRequest;
import com.aliyun.oss.model.CompleteMultipartUploadRequest;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.InitiateMultipartUploadRequest;
import com.aliyun.oss.model.ListPartsRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PartETag;
import com.aliyun.oss.model.PutObjectRequest;
import com.rubin.rpan.storage.ObjectUploadStorage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public final class OssStorageProcessor implements ObjectUploadStorage {
  public static final String TYPE = "com.rubin.rpan.storage.oss.OssStorageProcessor";
  private static final String MD5_METADATA = "cospace-md5";
  private static final String DIRECT_METADATA = "cospace-upload-id";
  public static final String PART_CONTENT_TYPE = "application/octet-stream";
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
  private static final Pattern LEGACY_KEY = Pattern.compile("[0-9]{8}/[a-fA-F0-9-]+(?:\\.[^/]+)?");

  private final OSS client;
  private final String bucket;
  private final String locatorPrefix;

  public OssStorageProcessor(OSS client, String bucket) {
    if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) {
      throw new IllegalArgumentException("Invalid OSS bucket name");
    }
    this.client = client;
    this.bucket = bucket;
    locatorPrefix = "oss://" + bucket + "/cospace/files/";
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
    return locatorPrefix + uploadId;
  }

  @Override
  public boolean ownsLocator(String locator) {
    return locator != null
        && locator.startsWith(locatorPrefix)
        && UUID_PATTERN.matcher(locator.substring(locatorPrefix.length())).matches();
  }

  @Override
  public boolean matchesPublishedObject(String locator, long size, String md5) throws IOException {
    String key = objectKey(locator);
    try {
      ObjectMetadata metadata = client.getObjectMetadata(bucket, key);
      if (metadata.getContentLength() != size
          || !md5.equals(metadata.getUserMetadata().get(MD5_METADATA))) {
        throw new IOException("Existing OSS object does not match the upload");
      }
      return true;
    } catch (OSSException error) {
      if ("NoSuchKey".equals(error.getErrorCode())) return false;
      throw operationFailed("inspect", error);
    } catch (ClientException error) {
      throw new IOException("Could not inspect OSS object", error);
    }
  }

  @Override
  public void publish(String locator, Path verifiedFile, long size, String md5) throws IOException {
    String key = objectKey(locator);
    ObjectMetadata metadata = writeMetadata(size);
    metadata.setContentMD5(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(md5)));
    metadata.addUserMetadata(MD5_METADATA, md5);
    try {
      client.putObject(new PutObjectRequest(bucket, key, verifiedFile.toFile(), metadata));
    } catch (OSSException error) {
      if ("FileAlreadyExists".equals(error.getErrorCode())
          && matchesPublishedObject(locator, size, md5)) return;
      throw operationFailed("publish", error);
    } catch (ClientException error) {
      throw new IOException("Could not publish OSS object", error);
    }
  }

  @Override
  public String store(InputStream content, Long size, String name) throws IOException {
    String locator = locator(UUID.randomUUID().toString());
    String key = objectKey(locator);
    try {
      client.putObject(new PutObjectRequest(bucket, key, content, writeMetadata(size)));
      return locator;
    } catch (OSSException error) {
      throw operationFailed("store", error);
    } catch (ClientException error) {
      throw new IOException("Could not store OSS object", error);
    }
  }

  @Override
  public void read2OutputStream(String locator, OutputStream output) throws IOException {
    String key =
        locator != null && LEGACY_KEY.matcher(locator).matches() ? locator : objectKey(locator);
    try (OSSObject object = client.getObject(bucket, key)) {
      object.getObjectContent().transferTo(output);
    } catch (OSSException error) {
      throw operationFailed("read", error);
    } catch (ClientException error) {
      throw new IOException("Could not read OSS object", error);
    }
  }

  @Override
  public void delete(String locator) throws IOException {
    String key = objectKey(locator);
    try {
      client.deleteObject(bucket, key);
    } catch (OSSException error) {
      if (!"NoSuchKey".equals(error.getErrorCode())) throw operationFailed("delete", error);
    } catch (ClientException error) {
      throw new IOException("Could not delete OSS object", error);
    }
  }

  public String initiateMultipart(String locator, String uploadId, String md5) throws IOException {
    ObjectMetadata metadata = new ObjectMetadata();
    metadata.addUserMetadata(MD5_METADATA, md5);
    metadata.addUserMetadata(DIRECT_METADATA, uploadId);
    return multipart(
        "initialize multipart upload",
        () ->
            client
                .initiateMultipartUpload(
                    new InitiateMultipartUploadRequest(bucket, objectKey(locator), metadata))
                .getUploadId());
  }

  public String signPart(
      String locator,
      String multipartId,
      int number,
      long size,
      String contentMd5,
      Instant expiresAt)
      throws IOException {
    var request = new GeneratePresignedUrlRequest(bucket, objectKey(locator), HttpMethod.PUT);
    request.setExpiration(Date.from(expiresAt));
    request.setContentMD5(contentMd5);
    request.setContentType(PART_CONTENT_TYPE);
    request.addHeader("content-length", String.valueOf(size));
    request.addAdditionalHeaderName("content-length");
    request.addQueryParameter("partNumber", String.valueOf(number));
    request.addQueryParameter("uploadId", multipartId);
    return multipart(
        "sign multipart upload", () -> client.generatePresignedUrl(request).toString());
  }

  public record UploadedPart(int number, long size, String etag) {}

  public List<UploadedPart> listMultipartParts(String locator, String multipartId)
      throws IOException {
    return multipart(
        "list multipart parts",
        () -> {
          var request = new ListPartsRequest(bucket, objectKey(locator), multipartId);
          List<UploadedPart> result = new ArrayList<>();
          var listing = client.listParts(request);
          while (true) {
            listing
                .getParts()
                .forEach(
                    part ->
                        result.add(
                            new UploadedPart(
                                part.getPartNumber(), part.getSize(), part.getETag())));
            if (!listing.isTruncated()) return result;
            request.setPartNumberMarker(listing.getNextPartNumberMarker());
            listing = client.listParts(request);
          }
        });
  }

  public void completeMultipart(String locator, String multipartId, List<UploadedPart> parts)
      throws IOException {
    List<PartETag> etags = new ArrayList<>(parts.size());
    for (UploadedPart part : parts) etags.add(new PartETag(part.number(), part.etag()));
    var request =
        new CompleteMultipartUploadRequest(bucket, objectKey(locator), multipartId, etags);
    request.addHeader("x-oss-forbid-overwrite", "true");
    multipart("complete multipart upload", () -> client.completeMultipartUpload(request));
  }

  public void abortMultipart(String locator, String multipartId) throws IOException {
    String key = objectKey(locator);
    multipart(
        "abort multipart upload",
        () -> {
          try {
            client.abortMultipartUpload(new AbortMultipartUploadRequest(bucket, key, multipartId));
          } catch (OSSException error) {
            if (!"NoSuchUpload".equals(error.getErrorCode())) throw error;
          }
          try {
            client.listParts(new ListPartsRequest(bucket, key, multipartId));
            throw new IOException("OSS multipart session still exists after abort");
          } catch (OSSException error) {
            if (!"NoSuchUpload".equals(error.getErrorCode())) throw error;
          }
          return null;
        });
  }

  public Long multipartObjectSize(String locator, String uploadId, String md5) throws IOException {
    return multipart(
        "inspect multipart object",
        () -> {
          try {
            var metadata = client.getObjectMetadata(bucket, objectKey(locator));
            if (!uploadId.equals(metadata.getUserMetadata().get(DIRECT_METADATA))
                || !md5.equals(metadata.getUserMetadata().get(MD5_METADATA))) {
              throw new IOException("Existing OSS multipart object does not belong to the upload");
            }
            return metadata.getContentLength();
          } catch (OSSException error) {
            if ("NoSuchKey".equals(error.getErrorCode())) return null;
            throw error;
          }
        });
  }

  private <T> T multipart(String operation, OssCall<T> call) throws IOException {
    try {
      return call.execute();
    } catch (OSSException error) {
      throw operationFailed(operation, error);
    } catch (ClientException error) {
      throw new IOException("Could not " + operation + " in OSS", error);
    }
  }

  @FunctionalInterface
  private interface OssCall<T> {
    T execute() throws IOException;
  }

  private String objectKey(String locator) throws IOException {
    if (!ownsLocator(locator)) {
      throw new IOException("OSS locator is outside the configured bucket or object prefix");
    }
    return locator.substring(("oss://" + bucket + "/").length());
  }

  private static ObjectMetadata writeMetadata(long size) {
    ObjectMetadata metadata = new ObjectMetadata();
    metadata.setContentLength(size);
    metadata.setHeader("x-oss-forbid-overwrite", "true");
    return metadata;
  }

  private static IOException operationFailed(String operation, OSSException error) {
    return new IOException(
        "Could not "
            + operation
            + " OSS object, code="
            + error.getErrorCode()
            + ", requestId="
            + error.getRequestId(),
        error);
  }
}
