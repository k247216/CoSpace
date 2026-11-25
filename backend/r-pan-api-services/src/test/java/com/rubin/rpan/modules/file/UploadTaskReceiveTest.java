package com.rubin.rpan.modules.file;

import com.rubin.rpan.common.exception.RPanException;
import com.rubin.rpan.modules.file.upload.concurrent.UploadLocks;
import com.rubin.rpan.modules.file.upload.model.UploadTaskInitRequest;
import com.rubin.rpan.modules.file.upload.model.UploadTaskState;
import com.rubin.rpan.modules.file.upload.model.UploadTaskView;
import com.rubin.rpan.modules.file.upload.service.UploadTaskService;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.springframework.mock.web.MockMultipartFile;

public class UploadTaskReceiveTest {
  private UploadTaskFixture f;

  @Before
  public void setUp() throws Exception {
    f = new UploadTaskFixture(false);
  }

  @After
  public void tearDown() throws Exception {
    if (f != null) f.close();
  }

  private MockMultipartFile chunk(byte... bytes) {
    return new MockMultipartFile("file", "ignored.bin", "application/octet-stream", bytes);
  }

  private String init(long size) {
    return f.service.initialize(f.input(size), 1L).getUploadId();
  }

  private void reject(Runnable action) {
    try {
      action.run();
      Assert.fail("Expected request rejection");
    } catch (RPanException expected) {
    }
  }

  @Test
  public void initializationFreezesParametersAndGeneratesIndependentTasks() {
    UploadTaskView first = f.service.initialize(f.input(5), 1L),
        second = f.service.initialize(f.input(5), 1L);
    Assert.assertNotEquals(first.getUploadId(), second.getUploadId());
    Assert.assertEquals(2, first.getChunkSize().intValue());
    Assert.assertEquals(3, first.getTotalChunks().intValue());
    Assert.assertEquals(UploadTaskState.UPLOADING, first.getState());
    Assert.assertEquals(1L, f.tasks.selectById(first.getUploadId()).getUserId().longValue());
    Assert.assertEquals(
        FileLifecycleFixture.MD5, f.tasks.selectById(first.getUploadId()).getExpectedMd5());
  }

  @Test
  public void outOfOrderChunksProduceCorrectProgressButNotFinalFile() throws Exception {
    String id = init(5);
    f.service.receive(id, 2, chunk((byte) 5), 1L);
    f.service.receive(id, 0, chunk((byte) 1, (byte) 2), 1L);
    Assert.assertEquals(Arrays.asList(0, 2), f.service.status(id, 1L).getUploadedChunks());
    f.service.receive(id, 1, chunk((byte) 3, (byte) 4), 1L);
    UploadTaskView v = f.service.status(id, 1L);
    Assert.assertTrue(v.getAllChunksReceived());
    Assert.assertEquals(UploadTaskState.UPLOADING, v.getState());
    Assert.assertEquals(
        1,
        f.database
            .jdbc
            .queryForObject("SELECT COUNT(*) FROM r_pan_file", Integer.class)
            .intValue());
    Assert.assertArrayEquals(new byte[] {5}, Files.readAllBytes(f.part(id, 2)));
  }

  @Test
  public void repeatedSameChunkIsIdempotent() {
    String id = init(5);
    Assert.assertFalse(f.service.receive(id, 0, chunk((byte) 1, (byte) 2), 1L).getDuplicate());
    Assert.assertTrue(f.service.receive(id, 0, chunk((byte) 1, (byte) 2), 1L).getDuplicate());
    Assert.assertTrue(f.service.receive(id, 0, chunk((byte) 1, (byte) 2), 1L).getDuplicate());
    Assert.assertEquals(Collections.singletonList(0), f.service.status(id, 1L).getUploadedChunks());
  }

  @Test
  public void conflictingDuplicateDoesNotOverwriteCompletedBytes() throws Exception {
    String id = init(5);
    f.service.receive(id, 0, chunk((byte) 1, (byte) 2), 1L);
    reject(() -> f.service.receive(id, 0, chunk((byte) 3, (byte) 4), 1L));
    Assert.assertArrayEquals(new byte[] {1, 2}, Files.readAllBytes(f.part(id, 0)));
    Assert.assertEquals(Collections.singletonList(0), f.service.status(id, 1L).getUploadedChunks());
  }

  @Test
  public void rangeAndLengthAreValidatedBeforeWriting() {
    String id = init(5);
    reject(() -> f.service.receive(id, -1, chunk((byte) 1, (byte) 2), 1L));
    reject(() -> f.service.receive(id, 3, chunk((byte) 1), 1L));
    reject(() -> f.service.receive(id, 2, chunk((byte) 1, (byte) 2), 1L));
    Assert.assertFalse(Files.exists(f.part(id, 0)));
    Assert.assertTrue(f.service.status(id, 1L).getUploadedChunks().isEmpty());
  }

  @Test
  public void streamLengthIsCheckedEvenIfMultipartSizeClaimsOtherwise() {
    String id = init(5);
    MockMultipartFile dishonest =
        new MockMultipartFile("file", new byte[] {1}) {
          @Override
          public long getSize() {
            return 2;
          }
        };
    reject(() -> f.service.receive(id, 0, dishonest, 1L));
    Assert.assertFalse(Files.exists(f.part(id, 0)));
    Assert.assertTrue(f.service.status(id, 1L).getUploadedChunks().isEmpty());
  }

  @Test
  public void failedStreamDoesNotPublishOrMarkChunk() throws Exception {
    String id = init(5);
    MockMultipartFile broken =
        new MockMultipartFile("file", new byte[] {1, 2}) {
          @Override
          public InputStream getInputStream() {
            return new InputStream() {
              @Override
              public int read() throws IOException {
                throw new IOException("injected disconnect");
              }
            };
          }
        };
    reject(() -> f.service.receive(id, 0, broken, 1L));
    Assert.assertFalse(Files.exists(f.part(id, 0)));
    try (java.util.stream.Stream<Path> paths = Files.list(f.root.resolve(id).resolve("incoming"))) {
      Assert.assertEquals(0, paths.count());
    }
  }

  @Test
  public void redisFailureAfterPublicationCanBeReconciled() {
    String id = init(5);
    f.progress.failMark = true;
    reject(() -> f.service.receive(id, 0, chunk((byte) 1, (byte) 2), 1L));
    Assert.assertTrue(Files.exists(f.part(id, 0)));
    f.progress.failMark = false;
    Assert.assertEquals(Collections.singletonList(0), f.service.status(id, 1L).getUploadedChunks());
    Assert.assertTrue(f.service.receive(id, 0, chunk((byte) 1, (byte) 2), 1L).getDuplicate());
  }

  @Test
  public void redisUnavailableBeforeReceiveDoesNotWrite() {
    String id = init(5);
    f.progress.unavailable = true;
    reject(() -> f.service.receive(id, 0, chunk((byte) 1, (byte) 2), 1L));
    Assert.assertFalse(Files.exists(f.part(id, 0)));
  }

  @Test
  public void lostBitmapAndRecreatedServiceRecoverFromPublishedChunks() {
    String id = init(5);
    f.service.receive(id, 1, chunk((byte) 3, (byte) 4), 1L);
    f.progress.clear();
    UploadTaskService restarted = f.newService(f.progress, new UploadLocks());
    Assert.assertEquals(Collections.singletonList(1), restarted.status(id, 1L).getUploadedChunks());
  }

  @Test
  public void cachedBitCannotHideMissingFile() throws Exception {
    String id = init(5);
    f.service.receive(id, 0, chunk((byte) 1, (byte) 2), 1L);
    Files.delete(f.part(id, 0));
    Assert.assertTrue(f.service.status(id, 1L).getUploadedChunks().isEmpty());
  }

  @Test
  public void incompleteTemporaryFileIsNotCompleted() throws Exception {
    String id = init(5);
    Path incoming = Files.createDirectories(f.root.resolve(id).resolve("incoming"));
    Files.write(incoming.resolve("interrupted.tmp"), new byte[] {1});
    Assert.assertTrue(f.service.status(id, 1L).getUploadedChunks().isEmpty());
  }

  @Test
  public void taskOwnershipStateAndExpiryAreEnforced() {
    String id = init(5);
    reject(() -> f.service.receive(id, 0, chunk((byte) 1, (byte) 2), 2L));
    reject(() -> f.service.status(id, 2L));
    f.database.jdbc.update(
        "UPDATE r_pan_upload_task SET task_state='FINALIZING' WHERE upload_id=?", id);
    reject(() -> f.service.receive(id, 0, chunk((byte) 1, (byte) 2), 1L));
    f.database.jdbc.update(
        "UPDATE r_pan_upload_task SET task_state='UPLOADING' WHERE upload_id=?", id);
    f.service.setClock(
        Clock.fixed(
            f.tasks.selectById(id).getExpiresAt().toInstant().plusMillis(1), ZoneOffset.UTC));
    reject(() -> f.service.receive(id, 0, chunk((byte) 1, (byte) 2), 1L));
  }

  @Test
  public void invalidMetadataAndDirectoriesDoNotCreateTasks() {
    UploadTaskInitRequest bad = f.input(5);
    bad.setName("../../evil");
    reject(() -> f.service.initialize(bad, 1L));
    bad.setName("normal");
    bad.setParentId(200L);
    reject(() -> f.service.initialize(bad, 1L));
    bad.setParentId(100L);
    bad.setSize(-1L);
    reject(() -> f.service.initialize(bad, 1L));
    bad.setSize(5L);
    bad.setMd5("bad");
    reject(() -> f.service.initialize(bad, 1L));
    Assert.assertEquals(
        0,
        f.database
            .jdbc
            .queryForObject("SELECT COUNT(*) FROM r_pan_upload_task", Integer.class)
            .intValue());
  }

  @Test
  public void totalChunkLimitIsCheckedBeforeIntegerCast() {
    f.properties.setChunkSize(1);
    reject(() -> f.service.initialize(f.input(3221225472L), 1L));
  }

  @Test
  public void emptyFileUsesOneZeroLengthChunk() {
    String id = init(0);
    Assert.assertEquals(1, f.tasks.selectById(id).getTotalChunks().intValue());
    f.service.receive(id, 0, chunk(), 1L);
    Assert.assertTrue(f.service.status(id, 1L).getAllChunksReceived());
  }

  @Test
  public void concurrentConflictingRequestsKeepOnlyOneContent() throws Exception {
    String id = init(5);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger accepted = new AtomicInteger(), conflicts = new AtomicInteger();
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (byte value : new byte[] {1, 2})
        futures.add(
            pool.submit(
                () -> {
                  try {
                    if (!start.await(5, TimeUnit.SECONDS))
                      throw new AssertionError("start timeout");
                  } catch (InterruptedException e) {
                    throw new AssertionError(e);
                  }
                  try {
                    f.service.receive(id, 0, chunk(value, value), 1L);
                    accepted.incrementAndGet();
                  } catch (RPanException expected) {
                    conflicts.incrementAndGet();
                  }
                }));
      start.countDown();
      for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
      Assert.assertEquals(1, accepted.get());
      Assert.assertEquals(1, conflicts.get());
      byte[] saved = Files.readAllBytes(f.part(id, 0));
      Assert.assertEquals(saved[0], saved[1]);
      Assert.assertTrue(saved[0] == 1 || saved[0] == 2);
      Assert.assertEquals(
          Collections.singletonList(0), f.service.status(id, 1L).getUploadedChunks());
    } finally {
      start.countDown();
      pool.shutdownNow();
      pool.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void reportedSizeCannotHideAnOversizedStream() {
    String id = init(5);
    MockMultipartFile dishonest =
        new MockMultipartFile("file", new byte[] {1, 2, 3}) {
          @Override
          public long getSize() {
            return 2;
          }
        };
    reject(() -> f.service.receive(id, 0, dishonest, 1L));
    Assert.assertFalse(Files.exists(f.part(id, 0)));
  }

  @Test
  public void invalidUploadIdCannotEscapeStagingDirectory() {
    reject(() -> f.service.receive("../../evil", 0, chunk((byte) 1, (byte) 2), 1L));
    reject(() -> f.service.status("../evil", 1L));
  }

  @Test
  public void concurrentIdenticalRequestsPublishOnce() throws Exception {
    String id = init(5);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    AtomicInteger fresh = new AtomicInteger();
    try {
      List<Future<?>> results = new ArrayList<>();
      for (int i = 0; i < 12; i++)
        results.add(
            pool.submit(
                () -> {
                  if (!f.service.receive(id, 0, chunk((byte) 1, (byte) 2), 1L).getDuplicate())
                    fresh.incrementAndGet();
                }));
      for (Future<?> result : results) result.get(10, TimeUnit.SECONDS);
      Assert.assertEquals(1, fresh.get());
      Assert.assertEquals(
          Collections.singletonList(0), f.service.status(id, 1L).getUploadedChunks());
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void differentIndexesCanWriteConcurrently() throws Exception {
    String id = init(4);
    CountDownLatch streams = new CountDownLatch(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      MockMultipartFile file =
          new MockMultipartFile("file", new byte[] {1, 2}) {
            @Override
            public InputStream getInputStream() {
              streams.countDown();
              try {
                if (!streams.await(5, TimeUnit.SECONDS))
                  throw new AssertionError("Different chunks were serialized");
              } catch (InterruptedException e) {
                throw new AssertionError(e);
              }
              return new ByteArrayInputStream(new byte[] {1, 2});
            }
          };
      Future<?> a = pool.submit(() -> f.service.receive(id, 0, file, 1L)),
          b = pool.submit(() -> f.service.receive(id, 1, file, 1L));
      a.get(10, TimeUnit.SECONDS);
      b.get(10, TimeUnit.SECONDS);
      Assert.assertTrue(f.service.status(id, 1L).getAllChunksReceived());
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
