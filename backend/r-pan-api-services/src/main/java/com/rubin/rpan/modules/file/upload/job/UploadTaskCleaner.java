package com.rubin.rpan.modules.file.upload.job;

import com.rubin.rpan.modules.file.dao.RPanFileMapper;
import com.rubin.rpan.modules.file.dao.RPanUploadTaskMapper;
import com.rubin.rpan.modules.file.entity.RPanUploadTask;
import com.rubin.rpan.modules.file.quota.service.UserSpaceService;
import com.rubin.rpan.modules.file.upload.concurrent.UploadLocks;
import com.rubin.rpan.modules.file.upload.config.UploadProperties;
import com.rubin.rpan.modules.file.upload.model.UploadTaskState;
import com.rubin.rpan.modules.file.upload.service.OssDirectUploads;
import com.rubin.rpan.modules.file.upload.storage.UploadChunkStorage;
import com.rubin.rpan.modules.file.upload.storage.UploadProgressStore;
import com.rubin.rpan.storage.ObjectUploadStorageRegistry;
import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.locks.Lock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(
    prefix = "rpan.upload.gc",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class UploadTaskCleaner {
  private static final Logger log = LoggerFactory.getLogger(UploadTaskCleaner.class);
  private static final String LOCAL = "com.rubin.rpan.storage.local.LocalStorageProcessor";
  // A timed-out PUT can still be completing on the provider when the user cancels.
  private static final Duration OBJECT_CLEANUP_GRACE = Duration.ofMinutes(30);
  private final RPanUploadTaskMapper tasks;
  private final RPanFileMapper files;
  private final UploadChunkStorage storage;
  private final UploadProgressStore progress;
  private final UploadLocks locks;
  private final UploadProperties properties;
  private final TransactionTemplate transaction;
  private final String root;
  private final UserSpaceService space;
  private final ObjectUploadStorageRegistry objectStorage;
  private final OssDirectUploads ossDirect;
  private Clock clock = Clock.systemUTC();

  public UploadTaskCleaner(
      @Qualifier("rPanUploadTaskMapper") RPanUploadTaskMapper tasks,
      @Qualifier("rPanFileMapper") RPanFileMapper files,
      UploadChunkStorage storage,
      UploadProgressStore progress,
      UploadLocks locks,
      UploadProperties properties,
      PlatformTransactionManager manager,
      @Value("${rpan.storage.local.root-file-path}") String root,
      UserSpaceService space,
      ObjectUploadStorageRegistry objectStorage) {
    this.tasks = tasks;
    this.files = files;
    this.storage = storage;
    this.progress = progress;
    this.locks = locks;
    this.properties = properties;
    this.transaction = new TransactionTemplate(manager);
    this.root = root;
    this.space = space;
    this.objectStorage = objectStorage;
    this.ossDirect = new OssDirectUploads(tasks, objectStorage);
  }

  public void setClock(Clock clock) {
    this.clock = clock;
  }

  @Scheduled(
      initialDelayString = "${rpan.upload.gc.initial-delay-ms:60000}",
      fixedDelayString = "${rpan.upload.gc.scan-delay-ms:60000}")
  public synchronized void sweep() {
    if (!properties.getGc().isEnabled()) return;
    Date now = new Date(clock.millis());
    Date stale = new Date(now.getTime() - properties.getGc().getFinalizingRetentionMs());
    try {
      sweepType(LOCAL, now, stale);
      for (String type : objectStorage.types()) sweepType(type, now, stale);
    } catch (Exception e) {
      log.warn("Upload artifact scan failed", e);
    }
  }

  private void sweepType(String type, Date now, Date stale) {
    Date abandonedBefore =
        !LOCAL.equals(type) ? Date.from(now.toInstant().minus(OBJECT_CLEANUP_GRACE)) : null;
    for (RPanUploadTask task :
        tasks.selectGarbageCandidates(
            now, stale, type, abandonedBefore, properties.getGc().getBatchSize())) {
      cleanOne(task.getUploadId());
    }
  }

  public boolean cleanOne(String id) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("上传垃圾清理IO不允许放在外层数据库事务中");
    UploadChunkStorage.requireId(id);
    Lock guard = locks.task(id).writeLock();
    if (!guard.tryLock()) return false; // 在途接收/合并优先，不等待长文件IO。
    try {
      RPanUploadTask owner = tasks.selectById(id);
      if (owner == null) return false;
      RPanUploadTask task =
          transaction.execute(
              tx -> {
                space.lockTask(owner);
                RPanUploadTask current = tasks.selectForUpdate(id);
                if (!eligible(current)) return null;
                if (current.getTaskState() == UploadTaskState.UPLOADING
                    || current.getTaskState() == UploadTaskState.FINALIZING) {
                  if (tasks.expire(id, new Date(clock.millis())) != 1)
                    throw new IllegalStateException("Expire upload failed");
                  current.setTaskState(UploadTaskState.EXPIRED);
                }
                if (current.getTaskState() != UploadTaskState.COMPLETED) space.release(current);
                return current;
              });
      if (task == null) return false;
      if (LOCAL.equals(task.getStorageType())) {
        cleanupLocal(task);
      } else {
        cleanupObject(task);
      }
      progress.replace(id, Collections.emptyList(), 1);
      transaction.execute(
          tx -> {
            if (tasks.artifactsCleaned(id, new Date(clock.millis())) != 1)
              throw new IllegalStateException("Artifact cleanup result not recorded");
            return null;
          });
      return true;
    } catch (Exception failure) {
      log.warn("Upload artifact cleanup failed, uploadId={}", id, failure);
      try {
        String error =
            failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
        String message = error.substring(0, Math.min(1000, error.length()));
        transaction.execute(
            tx -> {
              tasks.artifactCleanupFailed(id, message, new Date(clock.millis()));
              return null;
            });
      } catch (Exception unavailable) {
        log.warn("Could not record upload cleanup failure, uploadId={}", id, unavailable);
      }
      return false;
    } finally {
      guard.unlock();
    }
  }

  private boolean eligible(RPanUploadTask task) {
    if (task == null || task.getArtifactsCleanedAt() != null) return false;
    if (!LOCAL.equals(task.getStorageType()) && objectStorage.find(task.getStorageType()) == null)
      return false;
    switch (task.getTaskState()) {
      case UPLOADING:
        return task.getExpiresAt().getTime() <= clock.millis();
      case FINALIZING:
        return task.getExpiresAt().getTime() <= clock.millis()
            && task.getUpdatedAt().getTime()
                <= clock.millis() - properties.getGc().getFinalizingRetentionMs();
      case EXPIRED:
      case FAILED:
        return LOCAL.equals(task.getStorageType())
            || task.getFinalPath() == null
            || task.getUpdatedAt()
                .toInstant()
                .isBefore(clock.instant().minus(OBJECT_CLEANUP_GRACE));
      case COMPLETED:
        return true;
      default:
        return false;
    }
  }

  private void cleanupLocal(RPanUploadTask task) throws IOException {
    Path destination = destination(task);
    boolean abandoned = task.getTaskState() != UploadTaskState.COMPLETED;
    if (abandoned) requireUnregistered(task, destination.toString());
    storage.removeChunks(task);
    removeMergingFiles(destination);
    if (abandoned) {
      if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
          && !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("Invalid abandoned formal file");
      }
      Files.deleteIfExists(destination);
    }
  }

  private void cleanupObject(RPanUploadTask task) throws IOException {
    var backend = objectStorage.find(task.getStorageType());
    String locator = task.getFinalPath();
    if (task.isDirectUpload()) {
      boolean abandoned = task.getTaskState() != UploadTaskState.COMPLETED;
      String expected = backend.locator(task.getUploadId());
      if (locator != null && !expected.equals(locator)) {
        throw new IOException("Upload object locator differs from configured bucket");
      }
      if (abandoned) requireUnregistered(task, expected);
      ossDirect.clean(task, abandoned);
      return;
    }
    if (task.getTaskState() != UploadTaskState.COMPLETED && locator != null) {
      if (!backend.locator(task.getUploadId()).equals(locator)) {
        throw new IOException("Upload object locator differs from configured bucket");
      }
      requireUnregistered(task, locator);
      // 拒绝清除元数据对不上的对象。404 表示对象未发布或已经回收。
      if (backend.matchesPublishedObject(locator, task.getFileSize(), task.getExpectedMd5())) {
        backend.delete(locator);
      }
    }
    storage.removeChunks(task);
  }

  private void requireUnregistered(RPanUploadTask task, String locator) throws IOException {
    if (task.getRealFileId() != null || files.countByRealPath(locator) != 0) {
      throw new IOException("Formal file already has metadata; manual inspection required");
    }
  }

  private Path destination(RPanUploadTask task) throws IOException {
    Path base = Paths.get(root).toAbsolutePath().normalize();
    if (Files.exists(base)) base = base.toRealPath();
    Path directory = base.resolve("uploaded");
    if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
        && (Files.isSymbolicLink(directory)
            || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)))
      throw new IOException("Invalid formal upload directory");
    Path expected = directory.resolve(task.getUploadId());
    if (task.getFinalPath() != null
        && !expected.equals(Paths.get(task.getFinalPath()).toAbsolutePath().normalize()))
      throw new IOException("Upload destination differs from configured storage root");
    return expected;
  }

  private void removeMergingFiles(Path destination) throws IOException {
    Path directory = destination.getParent();
    if (!Files.exists(directory)) return;
    try (DirectoryStream<Path> paths =
        Files.newDirectoryStream(directory, destination.getFileName() + "-*.merging")) {
      for (Path file : paths) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
          throw new IOException("Invalid merging file");
        Files.deleteIfExists(file);
      }
    }
  }
}
