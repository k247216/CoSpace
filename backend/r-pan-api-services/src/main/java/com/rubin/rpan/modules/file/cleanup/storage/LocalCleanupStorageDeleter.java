package com.rubin.rpan.modules.file.cleanup.storage;

import com.rubin.rpan.modules.file.entity.RPanFileCleanupTask;
import java.io.IOException;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalCleanupStorageDeleter implements CleanupStorageDeleter {
  private final String storageType;
  private final Path root;

  public LocalCleanupStorageDeleter(
      @Value("${rpan.storage.processor.type}") String storageType,
      @Value("${rpan.storage.local.root-file-path:}") String root) {
    this.storageType = storageType;
    this.root = root.isEmpty() ? null : Paths.get(root).toAbsolutePath().normalize();
  }

  @Override
  public void delete(RPanFileCleanupTask task) throws IOException {
    if (!"com.rubin.rpan.storage.local.LocalStorageProcessor".equals(storageType)
        || !storageType.equals(task.getStorageType())
        || root == null) {
      throw new UnsafeCleanupException("STORAGE_TYPE_MISMATCH");
    }
    final Path path;
    try {
      path = Paths.get(task.getStorageLocator()).toAbsolutePath().normalize();
    } catch (RuntimeException e) {
      throw new UnsafeCleanupException("INVALID_STORAGE_PATH");
    }
    if (!path.startsWith(root) || path.equals(root))
      throw new UnsafeCleanupException("PATH_OUTSIDE_ROOT");
    Path current;
    try {
      current = root.toRealPath();
    } catch (NoSuchFileException absent) {
      return;
    } // 整个内容根目录已不存在，没有内容可删除。
    for (Path part : root.relativize(path)) {
      current = current.resolve(part);
      if (Files.isSymbolicLink(current)) throw new UnsafeCleanupException("SYMLINK_NOT_ALLOWED");
    }
    if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
      throw new UnsafeCleanupException("DIRECTORY_NOT_ALLOWED");
    // 不存在也算成功；权限、非空目录、IO等失败必须抛出，不能忽略File.delete返回值。
    Files.deleteIfExists(current);
  }
}
