package com.rubin.rpan.modules.file.cleanup.service;

import com.rubin.rpan.modules.file.cleanup.config.CleanupProperties;
import com.rubin.rpan.modules.file.cleanup.storage.CleanupStorageDeleter;
import com.rubin.rpan.modules.file.cleanup.storage.UnsafeCleanupException;
import com.rubin.rpan.modules.file.constant.*;
import com.rubin.rpan.modules.file.dao.*;
import com.rubin.rpan.modules.file.entity.*;
import java.time.Clock;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class CleanupTaskExecutor {
  private static final Logger log = LoggerFactory.getLogger(CleanupTaskExecutor.class);
  private final RPanFileCleanupTaskMapper tasks;
  private final RPanFileMapper files;
  private final RPanUserFileMapper references;
  private final CleanupStorageDeleter storage;
  private final CleanupProperties properties;
  private final TransactionTemplate transaction;
  private Clock clock = Clock.systemUTC();

  public CleanupTaskExecutor(
      RPanFileCleanupTaskMapper tasks,
      @Qualifier("rPanFileMapper") RPanFileMapper files,
      @Qualifier("rPanUserFileMapper") RPanUserFileMapper references,
      CleanupStorageDeleter storage,
      CleanupProperties properties,
      PlatformTransactionManager manager) {
    this.tasks = tasks;
    this.files = files;
    this.references = references;
    this.storage = storage;
    this.properties = properties;
    // 在这里新建了编程式事务
    transaction = new TransactionTemplate(manager);
    // 每次领取/记录结果独立提交；execute入口禁止携带外层事务执行存储IO。
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public void setClock(Clock clock) {
    this.clock = clock;
  }

  public boolean execute(long taskId) {
    // 不允许调用方带着尚未结束的事务进入清理执行器
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Cleanup execution must start outside a database transaction");
    }
    // 这里不直接用@Transactional就是为了把事务边界明确地划在领取任务和记录结果两段
    RPanFileCleanupTask task = transaction.execute(status -> claim(taskId));
    if (task == null) return true;
    try {
      storage.delete(task); // 不在数据库事务中执行。
      // 这里是第二段事务
      transaction.execute(
          status -> {
            complete(task);
            return null;
          });
      return true;
    } catch (Exception e) {
      log.warn("Cleanup execution failed, taskId={}", taskId, e);
      return transaction.execute(status -> fail(task, e));
    }
  }

  private RPanFileCleanupTask claim(long taskId) {
    RPanFileCleanupTask hint = tasks.selectById(taskId);
    if (hint == null) {
      log.warn("Ignoring unknown cleanup task {}", taskId);
      return null;
    }
    // 所有阶段都按物理文件→任务顺序取锁，与引用生命周期协议一致。
    RPanFile file = files.selectByIdForUpdate(hint.getRealFileId());
    RPanFileCleanupTask task = tasks.selectByIdForUpdate(taskId);
    if (task == null || terminal(task)) return null;
    Date now = now();
    // 已有执行者领取任务且租约尚未到期，本次不重复领取；租约到期后才允许接管。
    if (task.getExecutionState() == CleanupExecutionState.PROCESSING
        && task.getLeaseUntil() != null
        && task.getLeaseUntil().after(now)) return null;
    // 执行尝试次数已达上限，转为人工处理，停止自动执行，避免无限重试。
    if (task.getExecutionAttempts() >= properties.getMaxAttempts()) {
      tasks.manual(taskId, "EXECUTION_ATTEMPTS_EXHAUSTED", now);
      return null;
    }
    // 文件必须存在、处于待清理状态，且当前路径与任务创建时的快照一致。
    // 任一条件不符都停止删除并转人工处理，避免按过期或异常任务误删内容。
    if (file == null
        || file.getLifecycleState() != FileLifecycleState.DELETING
        || !matchesLocator(file, task)) {
      tasks.manual(taskId, "FILE_STATE_OR_LOCATOR_MISMATCH", now);
      return null;
    }
    // 用加锁的当前读再次检查全部用户引用（包括回收站记录）；仍有引用就禁止删除。
    // 正常生命周期协议下不应出现此情况，因此转人工排查，而不是继续清理。
    if (!references.selectReferenceIdsForUpdate(file.getFileId()).isEmpty()) {
      tasks.manual(taskId, "FILE_STILL_REFERENCED", now);
      return null;
    }
    // 本次执行的唯一令牌
    String owner = UUID.randomUUID().toString();
    Date until = new Date(now.getTime() + properties.getLeaseMs());
    if (tasks.claim(taskId, owner, until, now) != 1)
      throw new IllegalStateException("Task claim failed");
    // 同步当前内存对象的字段并返回
    task.setLeaseOwner(owner);
    task.setLeaseUntil(until);
    task.setExecutionState(CleanupExecutionState.PROCESSING);
    task.setExecutionAttempts(task.getExecutionAttempts() + 1);
    return task;
  }

  private void complete(RPanFileCleanupTask claimed) {
    RPanFile file = files.selectByIdForUpdate(claimed.getRealFileId());
    RPanFileCleanupTask current = tasks.selectByIdForUpdate(claimed.getTaskId());
    // owner结果令牌的校验
    if (!owned(current, claimed)) return; // 旧执行者不能覆盖新租约或完成状态。
    if (file == null || file.getLifecycleState() != FileLifecycleState.DELETING)
      throw new IllegalStateException("File state changed");

    // files.markDeleted：把物理文件的 lifecycle_state 从 DELETING 改为 DELETED，保留元数据。
    // tasks.finish：核对任务仍为 PROCESSING 且 owner 匹配后，标记 SUCCEEDED，同时清空执行令牌、租约和失败原因，记录完成时间。
    // Mapper 返回受影响行数，两步都应恰好更新 1 行；不是 1 表示预期记录/状态未匹配。
    if (files.markDeleted(file.getFileId()) != 1
        || tasks.finish(current.getTaskId(), claimed.getLeaseOwner(), now()) != 1) {
      throw new IllegalStateException("Cleanup completion update failed");
    }
  }

  private boolean fail(RPanFileCleanupTask claimed, Exception error) {
    files.selectByIdForUpdate(claimed.getRealFileId());
    RPanFileCleanupTask current = tasks.selectByIdForUpdate(claimed.getTaskId());
    if (!owned(current, claimed)) return true;
    boolean manual =
        error instanceof UnsafeCleanupException
            || current.getExecutionAttempts() >= properties.getMaxAttempts();
    String reason =
        error instanceof UnsafeCleanupException
            ? error.getMessage()
            : "DELETE_OR_COMPLETION_FAILED: " + error.getClass().getSimpleName();
    Date now = now();
    if (tasks.fail(
            current.getTaskId(),
            claimed.getLeaseOwner(),
            manual ? "MANUAL" : "RETRY",
            reason,
            new Date(now.getTime() + properties.getRetryDelayMs()),
            now)
        != 1) throw new IllegalStateException("Cleanup failure update failed");
    return manual;
  }

  public boolean retryManual(long taskId) {
    return transaction.execute(
        status -> {
          RPanFileCleanupTask hint = tasks.selectById(taskId);
          if (hint == null) return false;
          RPanFile file = files.selectByIdForUpdate(hint.getRealFileId());
          RPanFileCleanupTask task = tasks.selectByIdForUpdate(taskId);
          if (task == null
              || task.getExecutionState() != CleanupExecutionState.MANUAL
              || file == null
              || file.getLifecycleState() != FileLifecycleState.DELETING
              || !matchesLocator(file, task)
              || !references.selectReferenceIdsForUpdate(file.getFileId()).isEmpty()) return false;
          return tasks.retryManual(taskId, now()) == 1;
        });
  }

  private boolean matchesLocator(RPanFile file, RPanFileCleanupTask task) {
    return Objects.equals(file.getRealPath(), task.getStorageLocator())
        && (file.getStorageType() == null || file.getStorageType().equals(task.getStorageType()));
  }

  private boolean terminal(RPanFileCleanupTask t) {
    return t.getExecutionState() == CleanupExecutionState.SUCCEEDED
        || t.getExecutionState() == CleanupExecutionState.MANUAL;
  }

  private boolean owned(RPanFileCleanupTask current, RPanFileCleanupTask claimed) {
    return current != null
        && current.getExecutionState() == CleanupExecutionState.PROCESSING
        && Objects.equals(current.getLeaseOwner(), claimed.getLeaseOwner());
  }

  private Date now() {
    return new Date(clock.millis());
  }
}
