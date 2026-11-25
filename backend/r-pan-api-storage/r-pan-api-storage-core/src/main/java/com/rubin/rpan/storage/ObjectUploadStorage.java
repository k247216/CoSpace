package com.rubin.rpan.storage;

import java.io.IOException;
import java.nio.file.Path;

public interface ObjectUploadStorage extends StorageProcessor {
  String storageType();

  String locator(String uploadId);

  boolean ownsLocator(String locator);

  boolean matchesPublishedObject(String locator, long size, String md5) throws IOException;

  void publish(String locator, Path verifiedFile, long size, String md5) throws IOException;
}
