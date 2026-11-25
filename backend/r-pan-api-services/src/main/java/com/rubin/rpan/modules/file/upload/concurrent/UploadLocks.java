package com.rubin.rpan.modules.file.upload.concurrent;

import java.util.concurrent.locks.*;
import org.springframework.stereotype.Component;

/** 单JVM的有界条带锁：不同片可并行，同片互斥；任务写锁用于进度重建、收尾与过期清理。 */
@Component
public class UploadLocks {
  private final ReentrantReadWriteLock[] tasks = new ReentrantReadWriteLock[256];
  private final ReentrantLock[] chunks = new ReentrantLock[1024];

  public UploadLocks() {
    for (int i = 0; i < tasks.length; i++) tasks[i] = new ReentrantReadWriteLock(true);
    for (int i = 0; i < chunks.length; i++) chunks[i] = new ReentrantLock();
  }

  public ReentrantReadWriteLock task(String id) {
    return tasks[(id.hashCode() & 0x7fffffff) % tasks.length];
  }

  public Lock chunk(String id, int index) {
    return chunks[((31 * id.hashCode() + index) & 0x7fffffff) % chunks.length];
  }
}
