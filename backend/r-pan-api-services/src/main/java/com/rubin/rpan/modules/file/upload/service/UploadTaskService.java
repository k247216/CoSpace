package com.rubin.rpan.modules.file.upload.service;

import com.rubin.rpan.common.exception.RPanException;
import com.rubin.rpan.modules.file.dao.*;
import com.rubin.rpan.modules.file.entity.*;
import com.rubin.rpan.modules.file.quota.model.UploadReservation;
import com.rubin.rpan.modules.file.quota.service.UserSpaceService;
import com.rubin.rpan.modules.file.upload.concurrent.UploadLocks;
import com.rubin.rpan.modules.file.upload.config.UploadProperties;
import com.rubin.rpan.modules.file.upload.limit.UploadInitRateLimiter;
import com.rubin.rpan.modules.file.upload.model.ChunkReceipt;
import com.rubin.rpan.modules.file.upload.model.UploadTaskInitRequest;
import com.rubin.rpan.modules.file.upload.model.UploadTaskState;
import com.rubin.rpan.modules.file.upload.model.UploadTaskView;
import com.rubin.rpan.modules.file.upload.storage.RedisUploadProgressStore;
import com.rubin.rpan.modules.file.upload.storage.UploadChunkStorage;
import com.rubin.rpan.modules.file.upload.storage.UploadProgressStore;
import com.rubin.rpan.modules.workspace.WorkspaceUploads;
import com.rubin.rpan.storage.ObjectUploadStorageRegistry;
import java.io.IOException;
import java.time.Clock;
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
import org.springframework.web.multipart.MultipartFile;

@Service
public class UploadTaskService {
  private static final Logger log = LoggerFactory.getLogger(UploadTaskService.class);
  private static final String LOCAL = "com.rubin.rpan.storage.local.LocalStorageProcessor";
  private final RPanUploadTaskMapper tasks;
  private final RPanUserFileMapper userFiles;
  private final UploadProgressStore progress;
  private final UploadChunkStorage storage;
  private final UploadLocks locks;
  private final UploadProperties properties;
  private final TransactionTemplate transaction;
  private final UserSpaceService space;
  private final ObjectUploadStorageRegistry objectStorage;
  private final OssDirectUploads ossDirect;
  private final UploadInitRateLimiter limiter;
  private final String storageType;

  @Autowired(required = false)
  private WorkspaceUploads team;

  private Clock clock = Clock.systemUTC();

  public UploadTaskService(
      @Qualifier("rPanUploadTaskMapper") RPanUploadTaskMapper tasks,
      @Qualifier("rPanUserFileMapper") RPanUserFileMapper userFiles,
      UploadProgressStore progress,
      UploadChunkStorage storage,
      UploadLocks locks,
      UploadProperties properties,
      PlatformTransactionManager manager,
      @Value("${rpan.upload.storage-type:${rpan.storage.processor.type}}") String storageType,
      UserSpaceService space,
      UploadInitRateLimiter limiter,
      ObjectUploadStorageRegistry objectStorage) {
    this.tasks = tasks;
    this.userFiles = userFiles;
    this.progress = progress;
    this.storage = storage;
    this.locks = locks;
    this.properties = properties;
    this.transaction = new TransactionTemplate(manager);
    this.storageType = storageType;
    this.space = space;
    this.limiter = limiter;
    this.objectStorage = objectStorage;
    this.ossDirect = new OssDirectUploads(tasks, objectStorage);
  }

  public void setClock(Clock clock) {
    this.clock = clock;
  }

  public UploadTaskView initialize(UploadTaskInitRequest input, Long userId) {
    return initialize(input, userId, null);
  }

  public UploadTaskView initialize(UploadTaskInitRequest input, Long userId, Long workspaceId) {
    return initialize(input, userId, workspaceId, null);
  }

  public UploadTaskView initialize(
      UploadTaskInitRequest input, Long userId, Long workspaceId, String collectionId) {
    requireNoOuterTransaction();
    requireUser(userId); // 确认已经登陆
    requireStorage(storageType);
    validateInput(input); // 文件校验合法
    limiter.check(userId); // HTTP 429/503在数据库事务与预占之前返回。
    // 短事务，检查目标目录及其祖先：属于当前用户、确实是目录、没有被删除。目录检查通过，才开始创建任务对象。
    RPanUploadTask task =
        transaction.execute(
            status -> {
              // 锁用户的空间账户
              if (workspaceId == null) space.lock(userId);
              else team.lock(workspaceId);
              String requestId =
                  input.getRequestId() == null
                      ? UUID.randomUUID().toString()
                      : input.getRequestId();
              // 查这次初始化是否已经处理过，查的是上传空间预占明细表
              UploadReservation previous =
                  workspaceId == null
                      ? space.findRequest(userId, requestId)
                      : team.findRequest(workspaceId, userId, requestId);
              if (previous != null) {
                RPanUploadTask existing = owned(previous.getUploadId(), userId);
                if (!Objects.equals(existing.getCollectionId(), collectionId))
                  throw new RPanException("初始化请求不能改变收集任务");
                if (!existing.getFilename().equals(input.getName())
                    || existing.getFileSize().longValue() != input.getSize()
                    || !existing.getParentId().equals(input.getParentId())
                    || !existing.getExpectedMd5().equalsIgnoreCase(input.getMd5()))
                  throw new RPanException("同一初始化请求不能改变文件参数");
                return existing;
              }
              if (workspaceId == null) validateParent(input.getParentId(), userId);
              RPanUploadTask t = new RPanUploadTask();
              t.setUploadId(UUID.randomUUID().toString());
              t.setUserId(userId);
              t.setWorkspaceId(workspaceId);
              t.setCollectionId(collectionId);
              t.setParentId(input.getParentId());

              t.setFilename(input.getName());
              t.setExpectedMd5(input.getMd5().toLowerCase(Locale.ROOT));
              t.setFileSize(input.getSize());
              boolean direct =
                  properties.isOssDirectEnabled()
                      && input.isDirectUpload()
                      && input.getSize() > 0
                      && ossDirect.supports(storageType);
              t.setDirectUpload(direct);
              int chunkSize = direct ? OssDirectUploads.PART_SIZE : properties.getChunkSize();
              t.setChunkSize(chunkSize);
              long count = Math.max(1, (input.getSize() + chunkSize - 1) / chunkSize);
              if (count > properties.getMaxChunks()) throw new RPanException("分片数量超过限制");
              t.setTotalChunks((int) count);
              t.setStorageType(storageType);
              t.setTaskState(UploadTaskState.UPLOADING);
              Date now = new Date(clock.millis());
              t.setCreatedAt(now);
              t.setUpdatedAt(now);
              t.setExpiresAt(new Date(now.getTime() + properties.getTaskTtlMs()));
              if (workspaceId != null) t.setMembershipGeneration(team.initializeEligibility(t));
              space.reserve(t, requestId);
              if (tasks.insert(t) != 1) throw new RPanException("创建上传任务失败");
              return t;
            });
    if (!task.isDirectUpload()) return view(task, Collections.emptyList());
    Lock guard = locks.task(task.getUploadId()).writeLock();
    guard.lock();
    try {
      task = owned(task.getUploadId(), userId);
      if (task.getTaskState() == UploadTaskState.UPLOADING) {
        requireUploading(task);
        ossDirect.initialize(task);
        return directStatus(task);
      }
      return view(task, Collections.emptyList());
    } catch (IOException failure) {
      log.warn("OSS multipart initialization failed, uploadId={}", task.getUploadId(), failure);
      throw new RPanException("直传会话未就绪，请重试初始化");
    } finally {
      guard.unlock();
    }
  }

  public OssDirectUploads.SignedPart signPart(String id, int index, String md5, Long userId) {
    requireNoOuterTransaction();
    RPanUploadTask task = owned(id, userId);
    Lock guard = locks.task(id).writeLock();
    guard.lock();
    try {
      task = owned(id, userId);
      requireUploading(task);
      if (!task.isDirectUpload()) throw new RPanException("此任务不支持直传");
      if (task.getWorkspaceId() == null) validateParent(task.getParentId(), userId);
      else team.check(task);
      return ossDirect.sign(task, index, md5, clock.instant());
    } catch (IOException failure) {
      log.warn("OSS multipart signing failed, uploadId={}, part={}", id, index, failure);
      throw new RPanException("分片签名未就绪，请重试");
    } finally {
      guard.unlock();
    }
  }

  public ChunkReceipt receive(String id, int index, MultipartFile file, Long userId) {
    // 确认没有外层数据库事务：后面要写磁盘，不让整个写入过程被数据库事务包住
    requireNoOuterTransaction();
    // 查询并验证任务：任务属于当前用户，状态是 UPLOADING，并且没有过期。
    RPanUploadTask task = owned(id, userId);
    requireUploading(task);
    if (task.isDirectUpload()) throw new RPanException("此任务需通过 OSS 上传分片");
    // 按任务规则计算当前片应有的大小：第 0 片应为 2 MiB；第 2 片才是剩下的 1 MiB
    long expected = UploadChunkStorage.expectedSize(task, index);
    if (file == null || file.getSize() != expected) throw new RPanException("分片大小不匹配");
    //	任务读锁：接收期间，阻止同一任务进入合并、清理等需要写锁的操作
    //  同片锁：同一个 uploadId、同一个 index 的两个请求不能同时写入
    // 任务读锁可以共享，所以同一任务的不同分片仍可以并行接收；同片锁则把重复请求排队。
    // 实际使用的是固定数量的条带锁，少数不同任务或分片也可能映射到同一把锁而排队。
    Lock taskLock = locks.task(id).readLock(), chunkLock = locks.chunk(id, index);
    taskLock.lock();
    chunkLock.lock();
    try {
      task = owned(id, userId);
      requireUploading(task);
      // Redis不可用时先拒绝接收；写完后Redis再失败也保留完整分片，允许重试修复。
      readProgress(id);
      ChunkReceipt receipt = storage.receive(task, index, file);
      task = owned(id, userId);
      requireUploading(task);
      mark(id, index, remaining(task));
      return receipt;
    } catch (IOException e) {
      log.warn("Chunk storage failed, uploadId={}, chunk={}", id, index, e);
      throw new RPanException("分片存储未完成，请重试或检查存储");
    } finally {
      chunkLock.unlock();
      taskLock.unlock();
    }
  }

  public UploadTaskView status(String id, Long userId) {
    requireNoOuterTransaction();
    RPanUploadTask task = owned(id, userId);
    // 写锁与所有接收请求互斥，防止重建位图时覆盖并发置位。后续收尾也使用此任务写锁。
    Lock taskLock = locks.task(id).writeLock();
    taskLock.lock();
    try {
      task = owned(id, userId);
      if (task.getTaskState() != UploadTaskState.UPLOADING) {
        return view(task, Collections.emptyList());
      }
      requireUploading(task);
      if (task.isDirectUpload()) return directStatus(task);
      byte[] raw = readProgress(id);
      List<Integer> indexes = RedisUploadProgressStore.decode(raw, task.getTotalChunks());
      boolean rebuild = raw == null;
      if (!rebuild)
        for (int index : indexes)
          if (!storage.existsComplete(task, index)) {
            rebuild = true;
            break;
          }
      if (rebuild) {
        indexes = storage.listComplete(task);
        replace(id, indexes, remaining(task));
      }
      return view(task, indexes);
    } catch (IOException e) {
      log.warn("Chunk progress reconciliation failed, uploadId={}", id, e);
      throw new RPanException("已完成分片校验失败，暂不能报告上传进度");
    } finally {
      taskLock.unlock();
    }
  }

  public UploadTaskView cancel(String id, Long userId) {
    requireNoOuterTransaction();
    owned(id, userId);
    Lock guard = locks.task(id).writeLock();
    guard.lock();
    try {
      RPanUploadTask owner = owned(id, userId);
      RPanUploadTask task =
          transaction.execute(
              tx -> {
                space.lockTask(owner);
                RPanUploadTask current = tasks.selectForUpdate(id);
                if (current == null || !userId.equals(current.getUserId()))
                  throw new RPanException("上传任务不存在");
                if (current.getTaskState() == UploadTaskState.COMPLETED)
                  throw new RPanException("文件已上传完成，请通过文件列表删除");
                if (current.getTaskState() == UploadTaskState.UPLOADING
                    || current.getTaskState() == UploadTaskState.FINALIZING) {
                  if (tasks.expire(id, new Date(clock.millis())) != 1)
                    throw new IllegalStateException("取消上传失败");
                  current.setTaskState(UploadTaskState.EXPIRED);
                }
                space.release(current);
                return current;
              });
      return view(task, Collections.emptyList());
    } finally {
      guard.unlock();
    }
  }

  private RPanUploadTask owned(String id, Long userId) {
    requireUser(userId);
    UploadChunkStorage.requireId(id);
    RPanUploadTask task = tasks.selectById(id);
    if (task == null || !Objects.equals(task.getUserId(), userId))
      throw new RPanException("上传任务不存在");
    requireStorage(task.getStorageType());
    return task;
  }

  private void requireUploading(RPanUploadTask task) {
    if (task.getWorkspaceId() != null) team.check(task);
    if (task.getTaskState() != UploadTaskState.UPLOADING) throw new RPanException("当前任务状态不允许接收分片");
    if (task.getExpiresAt().getTime() <= clock.millis()) throw new RPanException("上传任务已过期");
  }

  private long remaining(RPanUploadTask task) {
    return Math.max(1, task.getExpiresAt().getTime() - clock.millis());
  }

  private void validateInput(UploadTaskInitRequest input) {
    if (input != null && input.getRequestId() != null)
      UploadChunkStorage.requireId(input.getRequestId());
    if (input == null
        || input.getName() == null
        || input.getName().trim().isEmpty()
        || input.getName().length() > 255
        || input.getName().equals(".")
        || input.getName().equals("..")
        || input.getName().contains("/")
        || input.getName().contains("\\")
        || input.getName().chars().anyMatch(c -> Character.isISOControl(c))
        || input.getSize() == null
        || input.getSize() < 0
        || input.getSize() > properties.getMaxFileSize()
        || input.getMd5() == null
        || !input.getMd5().matches("[0-9a-fA-F]{32}")) throw new RPanException("上传任务参数不合法");
  }

  private void validateParent(Long id, Long user) {
    Set<Long> visited = new HashSet<>();
    while (id != null && id > 0 && visited.add(id)) {
      RPanUserFile f = userFiles.selectByFileIdAndUserId(id, user);
      if (f == null
          || !Objects.equals(f.getUserId(), user)
          || !Objects.equals(f.getFolderFlag(), 1)
          || !Objects.equals(f.getDelFlag(), 0)) break;
      if (Objects.equals(f.getParentId(), 0L)) return;
      id = f.getParentId();
    }
    throw new RPanException("目标目录不可用");
  }

  private void requireUser(Long user) {
    if (user == null || user <= 0) throw new RPanException("请先登录");
  }

  private void requireStorage(String type) {
    if (!LOCAL.equals(type) && objectStorage.find(type) == null) {
      throw new RPanException("上传任务的存储未配置");
    }
  }

  private void requireNoOuterTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("分片IO不允许放在外层数据库事务中");
  }

  private byte[] readProgress(String id) {
    try {
      return progress.read(id);
    } catch (RuntimeException e) {
      log.warn("Upload bitmap access failed, uploadId={}", id, e);
      throw new RPanException("上传进度服务暂不可用，请重试");
    }
  }

  private void mark(String id, int index, long ttl) {
    try {
      progress.mark(id, index, ttl);
    } catch (RuntimeException e) {
      log.warn("Published chunk bitmap update failed, uploadId={}, chunk={}", id, index, e);
      throw new RPanException("分片已保存但进度未确认，请重试");
    }
  }

  private void replace(String id, List<Integer> indexes, long ttl) {
    try {
      progress.replace(id, indexes, ttl);
    } catch (RuntimeException e) {
      log.warn("Upload bitmap access failed, uploadId={}", id, e);
      throw new RPanException("上传进度服务暂不可用，请重试");
    }
  }

  private UploadTaskView directStatus(RPanUploadTask task) throws IOException {
    return view(task, ossDirect.parts(task).stream().map(part -> part.number() - 1).toList());
  }

  private UploadTaskView view(RPanUploadTask task, List<Integer> indexes) {
    UploadTaskView v = new UploadTaskView();
    v.setUploadId(task.getUploadId());
    v.setDirectUpload(task.isDirectUpload());
    v.setName(task.getFilename());
    v.setSize(task.getFileSize());
    v.setParentId(task.getParentId());
    v.setChunkSize(task.getChunkSize());
    v.setTotalChunks(task.getTotalChunks());
    v.setState(task.getTaskState());
    v.setExpiresAt(task.getExpiresAt());
    v.setUploadedChunks(indexes);
    v.setAllChunksReceived(
        task.getTaskState() == UploadTaskState.COMPLETED
            || indexes.size() == task.getTotalChunks());
    v.setRealFileId(task.getRealFileId());
    v.setUserFileId(task.getUserFileId());
    v.setLastError(task.getLastError());
    return v;
  }
}
