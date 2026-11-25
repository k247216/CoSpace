package com.rubin.rpan.modules.file.service.impl;

import com.rubin.rpan.common.exception.RPanException;
import com.rubin.rpan.modules.file.constant.FileLifecycleState;
import com.rubin.rpan.modules.file.dao.RPanFileCleanupTaskMapper;
import com.rubin.rpan.modules.file.dao.RPanFileMapper;
import com.rubin.rpan.modules.file.dao.RPanUserFileMapper;
import com.rubin.rpan.modules.file.entity.RPanFile;
import com.rubin.rpan.modules.file.entity.RPanFileCleanupTask;
import com.rubin.rpan.util.IdGenerator;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
public class FileLifecycleService {
  @Autowired private RPanFileMapper rPanFileMapper;
  @Autowired private RPanUserFileMapper rPanUserFileMapper;
  @Autowired private RPanFileCleanupTaskMapper taskMapper;

  @Value("${rpan.storage.legacy-type:${rpan.storage.processor.type}}")
  private String storageType;

  public Map<Long, RPanFile> lockFiles(Collection<Long> ids) {
    requireTransaction();
    Map<Long, RPanFile> result = new LinkedHashMap<>();
    if (ids == null || ids.stream().anyMatch(id -> id == null || id <= 0)) {
      throw new RPanException("物理文件ID不合法");
    }
    for (Long id : new TreeSet<>(ids)) {
      RPanFile file = rPanFileMapper.selectByIdForUpdate(id);
      if (file == null) throw new RPanException("物理文件不存在");
      result.put(id, file);
    }
    return result;
  }

  public void lockActiveFiles(Collection<Long> ids) {
    for (RPanFile file : lockFiles(ids).values()) {
      if (file.getLifecycleState() != FileLifecycleState.ACTIVE) {
        throw new RPanException("文件正在清理或已被删除，请重新上传");
      }
    }
  }

  public void scheduleIfUnreferenced(Collection<Long> ids) {
    for (RPanFile file : lockFiles(ids).values()) {
      if (file.getLifecycleState() != FileLifecycleState.ACTIVE) continue;
      if (!rPanUserFileMapper.selectReferenceIdsForUpdate(file.getFileId()).isEmpty()) continue;
      if (rPanFileMapper.markDeleting(file.getFileId()) != 1) {
        throw new RPanException("文件清理状态更新失败");
      }
      RPanFileCleanupTask task = new RPanFileCleanupTask();
      task.setTaskId(IdGenerator.nextId());
      task.setRealFileId(file.getFileId());
      task.setStorageType(file.getStorageType() == null ? storageType : file.getStorageType());
      task.setStorageLocator(file.getRealPath());
      if (taskMapper.insert(task) != 1) throw new RPanException("保存清理任务失败");
    }
  }

  private void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("文件生命周期操作必须在数据库事务中执行");
    }
  }
}
