package com.rubin.rpan.modules.file;

import com.rubin.rpan.modules.file.cleanup.config.CleanupProperties;
import com.rubin.rpan.modules.file.cleanup.mq.CleanupMessageSender;
import com.rubin.rpan.modules.file.cleanup.mq.CleanupTaskPublisher;
import com.rubin.rpan.modules.file.cleanup.service.CleanupTaskExecutor;
import com.rubin.rpan.modules.file.dao.*;
import java.io.IOException;
import java.time.*;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

public class CleanupDeliveryTest {
  private FileLifecycleFixture f;
  private CleanupProperties p;

  @Before
  public void setUp() throws Exception {
    f = new FileLifecycleFixture(false);
    p = new CleanupProperties();
    f.jdbc.update("DELETE FROM r_pan_user_file WHERE file_id=101");
    f.tx.execute(
        s -> {
          f.lifecycle.scheduleIfUnreferenced(Collections.singleton(900L));
          return null;
        });
  }

  @After
  public void tearDown() {
    if (f != null) f.close();
  }

  private CleanupTaskPublisher publisher(CleanupMessageSender sender, Instant now) {
    CleanupTaskPublisher pub =
        new CleanupTaskPublisher(f.session.getMapper(RPanFileCleanupTaskMapper.class), sender, p);
    pub.setClock(Clock.fixed(now, ZoneOffset.UTC));
    return pub;
  }

  @Test
  public void unavailableMqLeavesTaskForLaterDelivery() {
    Instant now = Instant.now().plusSeconds(1);
    CleanupTaskPublisher failed =
        publisher(
            id -> {
              throw new IOException("offline");
            },
            now);
    failed.publishDue();
    Assert.assertEquals(
        "PENDING",
        f.jdbc.queryForObject("SELECT delivery_state FROM r_pan_file_cleanup_task", String.class));
    AtomicInteger sends = new AtomicInteger();
    CleanupTaskPublisher healthy = publisher(id -> sends.incrementAndGet(), now);
    healthy.publishDue();
    Assert.assertEquals(0, sends.get());
    healthy.setClock(Clock.fixed(now.plusMillis(p.getRedeliveryMs() + 1), ZoneOffset.UTC));
    healthy.publishDue();
    Assert.assertEquals(1, sends.get());
    Assert.assertEquals(
        "SENT",
        f.jdbc.queryForObject("SELECT delivery_state FROM r_pan_file_cleanup_task", String.class));
  }

  @Test
  public void sentButUnfinishedTaskIsDeliveredAgain() {
    Instant now = Instant.now().plusSeconds(1);
    AtomicInteger sends = new AtomicInteger();
    CleanupTaskPublisher pub = publisher(id -> sends.incrementAndGet(), now);
    pub.publishDue();
    pub.publishDue();
    Assert.assertEquals(1, sends.get());
    pub.setClock(Clock.fixed(now.plusMillis(p.getRedeliveryMs() + 1), ZoneOffset.UTC));
    pub.publishDue();
    Assert.assertEquals(2, sends.get());
  }

  @Test
  public void consumerCanFinishBeforeSendResultUpdate() {
    CleanupTaskExecutor executor =
        new CleanupTaskExecutor(
            f.session.getMapper(RPanFileCleanupTaskMapper.class),
            f.session.getMapper(RPanFileMapper.class),
            f.session.getMapper(RPanUserFileMapper.class),
            task -> {},
            p,
            new DataSourceTransactionManager(f.dataSource));
    CleanupTaskPublisher pub =
        publisher(id -> Assert.assertTrue(executor.execute(id)), Instant.now().plusSeconds(1));
    pub.publishDue();
    Assert.assertEquals(
        "SUCCEEDED",
        f.jdbc.queryForObject("SELECT execution_state FROM r_pan_file_cleanup_task", String.class));
    Assert.assertEquals("DELETED", f.state());
    Assert.assertEquals(
        "SENT",
        f.jdbc.queryForObject("SELECT delivery_state FROM r_pan_file_cleanup_task", String.class));
  }

  @Test
  public void lateSendFailureCannotOverwriteConsumersFailure() {
    CleanupTaskExecutor executor =
        new CleanupTaskExecutor(
            f.session.getMapper(RPanFileCleanupTaskMapper.class),
            f.session.getMapper(RPanFileMapper.class),
            f.session.getMapper(RPanUserFileMapper.class),
            task -> {
              throw new IOException("storage failure");
            },
            p,
            new DataSourceTransactionManager(f.dataSource));
    publisher(
            id -> {
              executor.execute(id);
              throw new IOException("send result unknown");
            },
            Instant.now().plusSeconds(1))
        .publishDue();
    Assert.assertTrue(
        f.jdbc
            .queryForObject("SELECT last_error FROM r_pan_file_cleanup_task", String.class)
            .startsWith("DELETE_OR_COMPLETION_FAILED"));
    Assert.assertEquals(
        "RETRY",
        f.jdbc.queryForObject("SELECT execution_state FROM r_pan_file_cleanup_task", String.class));
  }

  @Test
  public void manualTaskIsNotAutomaticallyRepublished() {
    f.jdbc.update("UPDATE r_pan_file_cleanup_task SET execution_state='MANUAL'");
    publisher(id -> Assert.fail("Manual task must not be sent"), Instant.now().plusSeconds(3600))
        .publishDue();
  }
}
