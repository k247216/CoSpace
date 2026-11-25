package com.rubin.rpan.modules.file.cleanup.storage;

import java.io.IOException;

public class UnsafeCleanupException extends IOException {
  public UnsafeCleanupException(String reason) {
    super(reason);
  }
}
