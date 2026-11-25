package com.rubin.rpan.modules.file.cleanup.storage;

import com.rubin.rpan.modules.file.entity.RPanFileCleanupTask;
import java.io.IOException;

public interface CleanupStorageDeleter {
  void delete(RPanFileCleanupTask task) throws IOException;
}
