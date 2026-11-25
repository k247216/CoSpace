package com.rubin.rpan.modules.file;

import com.rubin.rpan.modules.file.dao.*;
import com.rubin.rpan.modules.file.upload.concurrent.UploadLocks;
import com.rubin.rpan.modules.file.upload.config.UploadProperties;
import com.rubin.rpan.modules.file.upload.model.UploadTaskInitRequest;
import com.rubin.rpan.modules.file.upload.service.UploadTaskService;
import com.rubin.rpan.modules.file.upload.storage.UploadChunkStorage;
import com.rubin.rpan.modules.file.upload.storage.UploadProgressStore;
import java.io.InputStream;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class UploadTaskFixture implements AutoCloseable {
  static final com.rubin.rpan.storage.ObjectUploadStorageRegistry LOCAL_ONLY =
      new com.rubin.rpan.storage.ObjectUploadStorageRegistry(java.util.List.of());
  static final String LOCAL = "com.rubin.rpan.storage.local.LocalStorageProcessor";
  final FileLifecycleFixture database;
  final UploadProperties properties = new UploadProperties();
  final MemoryProgress progress = new MemoryProgress();
  final UploadLocks locks = new UploadLocks();
  final UploadChunkStorage storage;
  final Path root;
  final RPanUploadTaskMapper tasks;
  final com.rubin.rpan.modules.file.upload.limit.UploadInitRateLimiter limiter;
  UploadTaskService service;

  UploadTaskFixture(boolean mysql) throws Exception {
    database = new FileLifecycleFixture(mysql);
    try (Connection c = database.dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(
          c, new ClassPathResource("sql/migrations/20261113_upload_task.sql"));
      ScriptUtils.executeSqlScript(
          c, new ClassPathResource("sql/migrations/20261114_upload_artifact_cleanup.sql"));
    }
    Configuration config = database.session.getConfiguration();
    String resource = "mappers/file/RPanUploadTaskMapper.xml";
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
      new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
    }
    root = Files.createTempDirectory("rpan-upload-");
    properties.setStagingPath(root.toString());
    properties.setChunkSize(2);
    tasks = database.session.getMapper(RPanUploadTaskMapper.class);
    storage = new UploadChunkStorage(properties);
    com.rubin.rpan.modules.file.upload.limit.UploadRateLimitProperties limits =
        new com.rubin.rpan.modules.file.upload.limit.UploadRateLimitProperties();
    limits.setEnabled(false);
    limiter = new com.rubin.rpan.modules.file.upload.limit.UploadInitRateLimiter(null, limits);
    service = newService(progress, locks);
  }

  UploadTaskService newService(UploadProgressStore state, UploadLocks guards) {
    return new UploadTaskService(
        tasks,
        database.session.getMapper(RPanUserFileMapper.class),
        state,
        storage,
        guards,
        properties,
        new DataSourceTransactionManager(database.dataSource),
        LOCAL,
        database.space,
        limiter,
        LOCAL_ONLY);
  }

  UploadTaskInitRequest input(long size) {
    UploadTaskInitRequest r = new UploadTaskInitRequest();
    r.setName("data.bin");
    r.setSize(size);
    r.setMd5(FileLifecycleFixture.MD5);
    r.setParentId(100L);
    return r;
  }

  Path part(String id, int index) {
    return root.resolve(id).resolve("chunks").resolve(index + ".part");
  }

  @Override
  public void close() throws Exception {
    database.jdbc.execute("DROP TABLE r_pan_upload_task");
    database.close();
    try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
      for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
        Files.deleteIfExists(p);
    }
  }

  static class MemoryProgress implements UploadProgressStore {
    final Map<String, byte[]> data = new HashMap<>();
    boolean unavailable, failMark;

    public synchronized byte[] read(String id) {
      check();
      byte[] raw = data.get(id);
      return raw == null ? null : raw.clone();
    }

    public synchronized void mark(String id, int index, long ttl) {
      check();
      if (failMark) throw new IllegalStateException("injected mark failure");
      byte[] raw = data.getOrDefault(id, new byte[0]);
      raw = Arrays.copyOf(raw, Math.max(raw.length, (index >> 3) + 1));
      raw[index >> 3] |= (byte) (1 << (7 - (index & 7)));
      data.put(id, raw);
    }

    public synchronized void replace(String id, List<Integer> indexes, long ttl) {
      check();
      data.remove(id);
      for (int index : indexes) mark(id, index, ttl);
    }

    private void check() {
      if (unavailable) throw new IllegalStateException("injected Redis outage");
    }

    synchronized void clear() {
      data.clear();
    }
  }
}
