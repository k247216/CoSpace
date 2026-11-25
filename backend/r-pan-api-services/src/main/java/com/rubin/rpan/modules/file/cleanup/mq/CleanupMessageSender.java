package com.rubin.rpan.modules.file.cleanup.mq;

public interface CleanupMessageSender {
  void send(long taskId) throws Exception;
}
