package com.rubin.rpan.modules.file.upload.storage;

import java.util.List;

public interface UploadProgressStore {
  /** null代表缓存不存在，需要从完整分片重建。 */
  byte[] read(String uploadId);

  void mark(String uploadId, int index, long ttlMs);

  void replace(String uploadId, List<Integer> indexes, long ttlMs);
}
