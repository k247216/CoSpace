package com.rubin.rpan.modules.file;

import com.rubin.rpan.modules.file.cleanup.config.CleanupProperties;
import com.rubin.rpan.modules.file.cleanup.mq.CleanupTaskPublisher;
import com.rubin.rpan.modules.file.cleanup.mq.RocketMqCleanupTransport;
import com.rubin.rpan.modules.file.cleanup.service.CleanupTaskExecutor;
import com.rubin.rpan.modules.file.cleanup.storage.LocalCleanupStorageDeleter;
import com.rubin.rpan.modules.file.dao.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

public class CleanupRocketMqIntegrationTest {
  private FileLifecycleFixture f;
  private RocketMqCleanupTransport transport;
  private Path root, content;

  @Before
  public void setUp() throws Exception {
    Assume.assumeTrue(
        "Enable with -Dcleanup.mq.integration=true", Boolean.getBoolean("cleanup.mq.integration"));
    f = new FileLifecycleFixture(false);
    root = Files.createTempDirectory("rpan-mq-it-");
    content = Files.write(root.resolve("content.txt"), new byte[] {1, 2, 3});
    f.jdbc.update("UPDATE r_pan_file SET real_path=? WHERE file_id=900", content.toString());
    f.jdbc.update("DELETE FROM r_pan_user_file WHERE file_id=101");
    f.tx.execute(
        status -> {
          f.lifecycle.scheduleIfUnreferenced(Collections.singleton(900L));
          return null;
        });
  }

  @After
  public void tearDown() throws Exception {
    if (transport != null) transport.destroy();
    if (f != null) f.close();
    if (content != null) Files.deleteIfExists(content);
    if (root != null) Files.deleteIfExists(root);
  }

  @Test
  public void realBrokerDeliversTaskAndDuplicateIsHarmless() throws Exception {
    run(false);
  }

  @Test
  public void storageFailureThenOutboxResendCompletesThroughRealBroker() throws Exception {
    run(true);
  }

  private void run(boolean failOnce) throws Exception {
    CleanupProperties p = new CleanupProperties();
    p.setNameserver(System.getProperty("cleanup.mq.test.nameserver", "127.0.0.1:9876"));
    p.setTopic("rpan-cleanup-integration");
    p.setConsumerGroup("rpan-it-" + UUID.randomUUID());
    p.setProducerGroup("rpan-it-producer-" + UUID.randomUUID());
    p.setRedeliveryMs(1000);
    p.setRetryDelayMs(1000);
    AtomicInteger storageCalls = new AtomicInteger();
    CountDownLatch duplicateSeen = new CountDownLatch(failOnce ? 3 : 2);
    long taskId =
        f.session.getMapper(RPanFileCleanupTaskMapper.class).selectByRealFileId(900L).getTaskId();
    LocalCleanupStorageDeleter actual =
        new LocalCleanupStorageDeleter(
            "com.rubin.rpan.storage.local.LocalStorageProcessor", root.toString());
    CleanupTaskExecutor executor =
        new CleanupTaskExecutor(
            f.session.getMapper(RPanFileCleanupTaskMapper.class),
            f.session.getMapper(RPanFileMapper.class),
            f.session.getMapper(RPanUserFileMapper.class),
            task -> {
              if (storageCalls.incrementAndGet() == 1 && failOnce)
                throw new IOException("Injected storage failure");
              actual.delete(task);
            },
            p,
            new DataSourceTransactionManager(f.dataSource)) {
          @Override
          public boolean execute(long id) {
            try {
              return super.execute(id);
            } finally {
              if (id == taskId) duplicateSeen.countDown();
            }
          }
        };
    transport = new RocketMqCleanupTransport(p, executor);
    transport.ensureStarted(); // Fail clearly if Broker/topic is not available.
    CleanupTaskPublisher publisher =
        new CleanupTaskPublisher(
            f.session.getMapper(RPanFileCleanupTaskMapper.class), transport, p);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      publisher.publishDue();
      if ("SUCCEEDED"
          .equals(
              f.jdbc.queryForObject(
                  "SELECT execution_state FROM r_pan_file_cleanup_task", String.class))) break;
      Thread.sleep(100);
    }
    Assert.assertEquals(
        "SUCCEEDED",
        f.jdbc.queryForObject("SELECT execution_state FROM r_pan_file_cleanup_task", String.class));
    Assert.assertEquals("DELETED", f.state());
    Assert.assertFalse(Files.exists(content));
    transport.send(taskId);
    Assert.assertTrue("Duplicate should be consumed", duplicateSeen.await(10, TimeUnit.SECONDS));
    Assert.assertEquals(failOnce ? 2 : 1, storageCalls.get());
    Assert.assertEquals(
        failOnce ? 2 : 1,
        f.jdbc
            .queryForObject("SELECT execution_attempts FROM r_pan_file_cleanup_task", Integer.class)
            .intValue());
  }
}
