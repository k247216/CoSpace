package com.rubin.rpan.modules.file.cleanup.storage;

import com.rubin.rpan.modules.file.entity.RPanFileCleanupTask;
import com.rubin.rpan.storage.ObjectUploadStorageRegistry;
import java.io.IOException;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class CleanupStorageRouter implements CleanupStorageDeleter {
  private final LocalCleanupStorageDeleter local;
  private final ObjectUploadStorageRegistry objectStorage;

  public CleanupStorageRouter(
      LocalCleanupStorageDeleter local, ObjectUploadStorageRegistry objectStorage) {
    this.local = local;
    this.objectStorage = objectStorage;
  }

  @Override
  public void delete(RPanFileCleanupTask task) throws IOException {
    var backend = objectStorage.find(task.getStorageType());
    if (backend == null) {
      local.delete(task);
      return;
    }
    if (!backend.ownsLocator(task.getStorageLocator())) {
      throw new UnsafeCleanupException("OBJECT_STORAGE_LOCATOR_MISMATCH");
    }
    backend.delete(task.getStorageLocator());
  }
}
