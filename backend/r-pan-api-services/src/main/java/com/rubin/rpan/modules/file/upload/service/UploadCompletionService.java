package com.rubin.rpan.modules.file.upload.service;

import com.rubin.rpan.common.exception.RPanException;
import com.rubin.rpan.modules.file.constant.FileLifecycleState;
import com.rubin.rpan.modules.file.dao.*;
import com.rubin.rpan.modules.file.entity.*;
import com.rubin.rpan.modules.file.quota.service.UserSpaceService;
import com.rubin.rpan.modules.file.upload.concurrent.UploadLocks;
import com.rubin.rpan.modules.file.upload.model.UploadTaskState;
import com.rubin.rpan.modules.file.upload.storage.UploadChunkStorage;
import com.rubin.rpan.modules.file.upload.storage.UploadProgressStore;
import com.rubin.rpan.modules.workspace.WorkspaceUploads;
import com.rubin.rpan.storage.ObjectUploadStorageRegistry;
import com.rubin.rpan.util.FileUtil;
import com.rubin.rpan.util.IdGenerator;
import com.rubin.rpan.util.file.type.context.FileTypeContext;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.Lock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UploadCompletionService {
  private static final Logger log = LoggerFactory.getLogger(UploadCompletionService.class);
  private final RPanUploadTaskMapper tasks;
  private final RPanFileMapper files;
  private final RPanUserFileMapper references;
  private final UploadChunkStorage storage;
  private final UploadProgressStore progress;
  private final UploadLocks locks;
  private final TransactionTemplate transaction;
  private final String root;
  private final UserSpaceService space;
  private final ObjectUploadStorageRegistry objectStorage;
  private final OssDirectUploads ossDirect;

  @Autowired(required = false)
  private WorkspaceUploads team;

  private static final String LOCAL = "com.rubin.rpan.storage.local.LocalStorageProcessor";

  public UploadCompletionService(
      @Qualifier("rPanUploadTaskMapper") RPanUploadTaskMapper tasks,
      @Qualifier("rPanFileMapper") RPanFileMapper files,
      @Qualifier("rPanUserFileMapper") RPanUserFileMapper references,
      UploadChunkStorage storage,
      UploadProgressStore progress,
      UploadLocks locks,
      PlatformTransactionManager manager,
      @Value("${rpan.storage.local.root-file-path}") String root,
      UserSpaceService space,
      ObjectUploadStorageRegistry objectStorage) {
    this.tasks = tasks;
    this.files = files;
    this.references = references;
    this.storage = storage;
    this.progress = progress;
    this.locks = locks;
    this.transaction = new TransactionTemplate(manager);
    this.root = root;
    this.space = space;
    this.objectStorage = objectStorage;
    this.ossDirect = new OssDirectUploads(tasks, objectStorage);
  }

  public RPanUploadTask complete(String id, Long userId) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("合并文件不允许放在外层数据库事务中");
    UploadChunkStorage.requireId(id);
    // 任务写锁，读取任务并验证归属。已经是 COMPLETED 的任务，会跳过合并和入库，避免重复创建记录
    Lock guard = locks.task(id).writeLock();
    guard.lock();
    try {
      RPanUploadTask task = owned(tasks.selectById(id), userId);
      if (task.getTaskState() != UploadTaskState.COMPLETED) {
        String target = finalLocator(task);
        if (task.isDirectUpload() && task.getTaskState() == UploadTaskState.UPLOADING) {
          if (task.getExpiresAt().before(new Date())) throw new RPanException("上传任务已过期");
          try {
            if (ossDirect.parts(task).size() != task.getTotalChunks())
              throw new RPanException("分片尚未收齐");
          } catch (IOException failure) {
            log.warn("OSS multipart status check failed, uploadId={}", id, failure);
            throw new RPanException("分片检查失败，请重试");
          }
        }
        // 在长IO之前持久化收尾意图，重试仍使用同一路径。
        task =
            transaction.execute(
                tx -> {
                  space.lockTask(tasks.selectById(id));
                  RPanUploadTask current = owned(tasks.selectForUpdate(id), userId);
                  if (current.getTaskState() == UploadTaskState.UPLOADING) {
                    if (current.getExpiresAt().before(new Date()))
                      throw new RPanException("上传任务已过期");
                    try {
                      // listComplete 会扫描磁盘上的完整分片，并检查编号和长度。这里依据真实文件确认收齐，不只相信前端或 Bitmap。
                      if (!current.isDirectUpload()
                          && storage.listComplete(current).size() != current.getTotalChunks())
                        throw new RPanException("分片尚未收齐");
                    } catch (IOException e) {
                      throw new RPanException("分片检查失败，请重试");
                    }
                    if (current.getWorkspaceId() != null) team.check(current);
                    else if (current.isDirectUpload())
                      validateParent(current.getParentId(), userId);
                    current.setFinalPath(target);
                    if (tasks.startFinalizing(current) != 1) throw new RPanException("上传状态更新失败");
                    current.setTaskState(UploadTaskState.FINALIZING);
                  }
                  if (current.getTaskState() != UploadTaskState.FINALIZING)
                    throw new RPanException("当前上传任务不能完成");
                  if (!target.equals(current.getFinalPath()))
                    throw new RPanException("正式文件存储配置已变化");
                  return current;
                });
        try {
          publish(task); // 合并、校验及对象存储 I/O 都在数据库事务外。
        } catch (IOException e) {
          final boolean failed = e instanceof UploadChunkStorage.IntegrityException;
          transaction.execute(
              tx -> {
                space.lockTask(tasks.selectById(id));
                RPanUploadTask current = owned(tasks.selectForUpdate(id), userId);
                if (failed) space.release(current);
                tasks.recordError(id, failed ? e.getMessage() : "合并存储失败，请重试", failed);
                return null;
              });
          log.warn("Upload assembly failed, uploadId={}", id, e);
          throw new RPanException(failed ? e.getMessage() : "合并存储失败，请重试完成请求");
        }
        // 失败时两张业务表与任务完成状态一起回滚，保留正式文件供下次复用。
        try {
          task = transaction.execute(tx -> register(id, userId));
        } catch (RuntimeException failure) {
          try {
            transaction.execute(
                tx -> {
                  tasks.recordError(id, "元数据入库未完成，请重试完成请求", false);
                  return null;
                });
          } catch (RuntimeException unavailable) {
            log.warn("Could not record upload registration failure, uploadId={}", id, unavailable);
          }
          throw failure;
        }
      }
      cleanup(task);
      return task;
    } catch (IOException e) {
      throw new RPanException("正式文件目录不可用");
    } finally {
      guard.unlock();
    }
  }

  private RPanUploadTask register(String id, Long userId) {
    space.lockTask(tasks.selectById(id));
    RPanUploadTask task = owned(tasks.selectForUpdate(id), userId);
    if (task.getTaskState() == UploadTaskState.COMPLETED) return task;
    if (task.getTaskState() != UploadTaskState.FINALIZING) throw new RPanException("上传任务状态已变化");
    if (task.getWorkspaceId() == null) validateParent(task.getParentId(), userId);
    else team.check(task);
    Date now = new Date();
    RPanFile physical = new RPanFile();
    physical.setStorageType(task.getStorageType());
    physical.setFileId(IdGenerator.nextId());
    physical.setFilename(task.getUploadId());
    physical.setRealPath(task.getFinalPath());
    physical.setFileSize(String.valueOf(task.getFileSize()));
    physical.setFileSizeDesc(FileUtil.getFileSizeDesc(task.getFileSize()));
    physical.setFileSuffix(FileUtil.getFileSuffix(task.getFilename()));
    physical.setFilePreviewContentType(FileUtil.getContentType(task.getFilename()));
    physical.setMd5(task.getExpectedMd5());
    physical.setCreateUser(userId);
    physical.setCreateTime(now);
    physical.setLifecycleState(FileLifecycleState.ACTIVE);
    if (files.insertSelective(physical) != 1) throw new RPanException("物理文件记录保存失败");

    RPanUserFile reference = new RPanUserFile();
    reference.setFileId(IdGenerator.nextId());
    reference.setUserId(userId);
    reference.setParentId(task.getParentId());
    reference.setRealFileId(physical.getFileId());
    reference.setFilename(availableName(task));
    reference.setFolderFlag(0);
    reference.setDelFlag(0);
    reference.setFileType(FileTypeContext.getFileTypeCode(task.getFilename()));
    reference.setFileSizeDesc(physical.getFileSizeDesc());
    reference.setSpaceBytes(task.getFileSize());
    reference.setCreateUser(userId);
    reference.setUpdateUser(userId);
    reference.setCreateTime(now);
    reference.setUpdateTime(now);
    // 新物理记录尚未提交，其他事务无法引用或清理它；这里与首条引用一起提交。
    if ((task.getWorkspaceId() == null
            ? references.insertSelective(reference)
            : team.insertReference(task, reference))
        != 1) throw new RPanException("用户文件记录保存失败");
    task.setRealFileId(physical.getFileId());
    task.setUserFileId(reference.getFileId());
    space.commit(task);
    if (tasks.complete(task) != 1) throw new RPanException("上传完成状态保存失败");
    task.setTaskState(UploadTaskState.COMPLETED);
    task.setLastError(null);
    return task;
  }

  private String availableName(RPanUploadTask task) {
    String name = task.getFilename(), suffix = FileUtil.getFileSuffix(name);
    String base = name.substring(0, name.length() - suffix.length());
    Set<String> existing = new HashSet<>();
    if (task.getWorkspaceId() != null) return team.availableName(task);
    references
        .selectRPanUserFileVOListByUserIdAndFileTypeAndParentIdAndDelFlag(
            task.getUserId(), null, task.getParentId(), 0)
        .forEach(f -> existing.add(f.getFilename()));
    for (int i = 1; existing.contains(name); i++) name = base + "（" + i + "）" + suffix;
    return name;
  }

  private void validateParent(Long parent, Long user) {
    Set<Long> visited = new HashSet<>();
    while (parent != null && parent > 0 && visited.add(parent)) {
      RPanUserFile directory = references.selectByIdForUpdate(parent);
      if (directory == null
          || !user.equals(directory.getUserId())
          || !Integer.valueOf(1).equals(directory.getFolderFlag())
          || !Integer.valueOf(0).equals(directory.getDelFlag())) break;
      if (Long.valueOf(0).equals(directory.getParentId())) return;
      parent = directory.getParentId();
    }
    throw new RPanException("目标目录不可用");
  }

  private RPanUploadTask owned(RPanUploadTask task, Long user) {
    if (user == null || user <= 0 || task == null || !user.equals(task.getUserId()))
      throw new RPanException("上传任务不存在");
    if (!LOCAL.equals(task.getStorageType()) && objectStorage.find(task.getStorageType()) == null) {
      throw new RPanException("上传任务的存储未配置");
    }
    return task;
  }

  private String finalLocator(RPanUploadTask task) throws IOException {
    var backend = objectStorage.find(task.getStorageType());
    return backend == null
        ? localTarget(task.getUploadId()).toString()
        : backend.locator(task.getUploadId());
  }

  private void publish(RPanUploadTask task) throws IOException {
    if (task.isDirectUpload()) {
      ossDirect.complete(task);
      return;
    }
    var backend = objectStorage.find(task.getStorageType());
    if (backend == null) {
      storage.assemble(task);
      return;
    }
    String locator = task.getFinalPath();
    if (backend.matchesPublishedObject(locator, task.getFileSize(), task.getExpectedMd5())) return;
    Path verified = storage.assembleForObjectStorage(task);
    backend.publish(locator, verified, task.getFileSize(), task.getExpectedMd5());
  }

  private Path localTarget(String id) throws IOException {
    Path base = Paths.get(root).toAbsolutePath().normalize();
    Files.createDirectories(base);
    base = base.toRealPath();
    Path directory = base.resolve("uploaded");
    try {
      Files.createDirectory(directory);
    } catch (FileAlreadyExistsException ignored) {
    }
    if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Invalid upload destination");
    return directory.resolve(id);
  }

  private void cleanup(RPanUploadTask task) {
    try {
      storage.removeChunks(task);
    } catch (IOException e) {
      log.warn("Completed upload staging cleanup failed, uploadId={}", task.getUploadId(), e);
    }
    try {
      progress.replace(task.getUploadId(), Collections.emptyList(), 1);
    } catch (RuntimeException e) {
      log.warn("Completed upload bitmap cleanup failed, uploadId={}", task.getUploadId(), e);
    }
  }
}
