package com.rubin.rpan.modules.file.quota.service;

import com.rubin.rpan.common.exception.RPanException;
import com.rubin.rpan.modules.file.dao.UserSpaceMapper;
import com.rubin.rpan.modules.file.entity.RPanFile;
import com.rubin.rpan.modules.file.entity.RPanUploadTask;
import com.rubin.rpan.modules.file.quota.config.UserSpaceProperties;
import com.rubin.rpan.modules.file.quota.model.*;
import com.rubin.rpan.modules.workspace.WorkspaceUploads;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
public class UserSpaceService {
  @Autowired(required = false)
  private WorkspaceUploads team;

  private final UserSpaceMapper mapper;
  private final UserSpaceProperties properties;

  public UserSpaceService(UserSpaceMapper mapper, UserSpaceProperties properties) {
    this.mapper = mapper;
    this.properties = properties;
  }

  public void createAccount(Long userId) {
    requireTransaction();
    if (mapper.create(userId, properties.getDefaultTotalBytes()) != 1)
      throw new IllegalStateException("创建空间账户失败");
  }

  @Transactional(readOnly = true)
  public UserSpaceAccount view(Long userId) {
    return requireAccount(mapper.select(requireUser(userId)));
  }

  public UserSpaceAccount lock(Long userId) {
    requireTransaction();
    return requireAccount(mapper.lock(requireUser(userId)));
  }

  public UploadReservation findRequest(Long userId, String requestId) {
    requireTransaction();
    return mapper.findRequest(userId, requestId);
  }

  public void reserve(RPanUploadTask task, String requestId) {
    requireTransaction();
    nonnegative(task.getFileSize());
    if (task.getWorkspaceId() != null) {
      team.reserve(task, requestId);
      return;
    }
    if (mapper.reserve(task.getUserId(), task.getFileSize()) != 1) insufficient();
    UploadReservation r = new UploadReservation();
    r.setUploadId(task.getUploadId());
    r.setUserId(task.getUserId());
    r.setRequestId(requestId);
    r.setBytes(task.getFileSize());
    if (mapper.insertReservation(r) != 1) throw new IllegalStateException("保存空间预占失败");
  }

  public void lockTask(RPanUploadTask task) {
    if (task.getWorkspaceId() == null) lock(task.getUserId());
    else team.lock(task.getWorkspaceId());
  }

  public void commit(RPanUploadTask task) {
    settle(task, true);
  }

  public void release(RPanUploadTask task) {
    settle(task, false);
  }

  private void settle(RPanUploadTask task, boolean commit) {
    requireTransaction();
    if (task.getWorkspaceId() != null) {
      team.settle(task, commit);
      return;
    }
    UploadReservation r = mapper.lockReservation(task.getUploadId());
    if (r == null || !task.getUserId().equals(r.getUserId()) || r.getBytes() != task.getFileSize())
      throw new IllegalStateException("上传空间预占记录不一致");
    String target = commit ? "COMMITTED" : "RELEASED";
    if (target.equals(r.getState())) return;
    if (!"RESERVED".equals(r.getState())) throw new IllegalStateException("上传空间已结算，不能改变结算结果");
    if (mapper.settle(task.getUserId(), r.getBytes(), commit) != 1
        || mapper.settleReservation(task.getUploadId(), target) != 1)
      throw new IllegalStateException("空间结算失败，事务已回滚");
  }

  public void charge(Long userId, long bytes) {
    requireTransaction();
    nonnegative(bytes);
    if (mapper.charge(userId, bytes) != 1) insufficient();
  }

  public void releaseUsed(Long userId, long bytes) {
    requireTransaction();
    nonnegative(bytes);
    if (mapper.releaseUsed(userId, bytes) != 1) throw new IllegalStateException("空间账户与文件记录不一致");
  }

  public static long fileBytes(RPanFile file) {
    try {
      long value = Long.parseLong(file.getFileSize());
      nonnegative(value);
      return value;
    } catch (RuntimeException e) {
      throw new IllegalStateException("物理文件大小异常，无法计费", e);
    }
  }

  private static void nonnegative(long value) {
    if (value < 0) throw new IllegalArgumentException("容量字节数不能为负");
  }

  private static Long requireUser(Long id) {
    if (id == null || id <= 0) throw new RPanException("请先登录");
    return id;
  }

  private static UserSpaceAccount requireAccount(UserSpaceAccount a) {
    if (a == null) throw new IllegalStateException("用户空间账户不存在，请检查容量迁移");
    return a;
  }

  private static void insufficient() {
    throw new RPanException(4101, "空间不足，请清理文件或取消未完成上传");
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("容量修改必须加入业务事务");
  }
}
