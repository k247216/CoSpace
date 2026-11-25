package com.rubin.rpan.modules.file;

import static org.junit.Assert.*;

import com.rubin.rpan.common.exception.RPanException;
import com.rubin.rpan.modules.file.dao.RPanFileMapper;
import com.rubin.rpan.modules.file.dao.RPanUserFileMapper;
import com.rubin.rpan.modules.file.upload.service.UploadCompletionService;
import com.rubin.rpan.modules.workspace.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

public class WorkspaceFilePipelineTest {
  @Test
  public void completedTeamFileIsIsolatedAndRemovedMemberCannotContinueOldTask() throws Exception {
    try (var f = new TeamFixture()) {
      var input = f.fixture.input(3);
      input.setMd5("900150983cd24fb0d6963f7d28e17f72");
      input.setParentId(f.workspace);
      var task = f.fixture.service.initialize(input, 2L, f.workspace);
      f.fixture.service.receive(
          task.getUploadId(), 0, new MockMultipartFile("file", new byte[] {97, 98}), 2L);
      f.fixture.service.receive(
          task.getUploadId(), 1, new MockMultipartFile("file", new byte[] {99}), 2L);
      var done = f.completion.complete(task.getUploadId(), 2L);
      assertEquals(
          done.getUserFileId(), f.completion.complete(task.getUploadId(), 2L).getUserFileId());
      assertEquals(3, f.used());
      assertEquals(0, f.reserved());
      assertEquals(
          1,
          f.fixture.database.tx.execute(status -> f.files.search(f.workspace, 4, "data")).size());
      assertThrows(
          RPanException.class,
          () -> f.fixture.database.tx.execute(status -> f.files.search(f.workspace, 3, "data")));
      assertEquals(
          0,
          f.fixture
              .database
              .jdbc
              .queryForObject(
                  "SELECT COUNT(*) FROM r_pan_user_file WHERE user_id=2 AND file_id=?",
                  Integer.class,
                  done.getUserFileId())
              .intValue());
      assertEquals(
          1,
          f.fixture
              .database
              .tx
              .execute(status -> f.files.list(f.workspace, 4, f.workspace, false))
              .size());
      assertThrows(
          RPanException.class,
          () ->
              f.fixture.database.tx.execute(
                  status -> f.files.downloadable(f.workspace, 3, done.getUserFileId())));
      var pending = f.fixture.service.initialize(input, 2L, f.workspace);
      f.fixture.database.tx.executeWithoutResult(status -> f.governance.remove(f.workspace, 1, 2));
      assertThrows(RPanException.class, () -> f.completion.complete(pending.getUploadId(), 2L));
      f.fixture.database.tx.executeWithoutResult(
          status -> f.governance.assign(f.workspace, 1, 2, WorkspaceRole.MEMBER));
      assertThrows(
          RPanException.class,
          () ->
              f.fixture.service.receive(
                  pending.getUploadId(),
                  0,
                  new MockMultipartFile("file", new byte[] {97, 98}),
                  2L));
      f.fixture.service.cancel(pending.getUploadId(), 2L);
      assertEquals(0, f.reserved());
      assertEquals(3, f.used());
      f.fixture.database.tx.executeWithoutResult(
          status -> f.files.recycle(f.workspace, 2, done.getUserFileId(), false, false));
      assertEquals(3, f.used());
      f.fixture.database.tx.executeWithoutResult(
          status -> f.files.recycle(f.workspace, 1, done.getUserFileId(), false, true));
      assertEquals(0, f.used());
      assertEquals(
          "PENDING",
          f.fixture.database.jdbc.queryForObject(
              "SELECT execution_state FROM r_pan_file_cleanup_task WHERE real_file_id=?",
              String.class,
              done.getRealFileId()));
    }
  }

  @Test
  public void onlyOneOfTwentyConcurrentUploadsCanReserveTheLastTeamCapacity() throws Exception {
    try (var f = new TeamFixture();
        var pool = Executors.newFixedThreadPool(20)) {
      f.fixture.database.jdbc.update(
          "UPDATE r_pan_workspace SET total_bytes=5 WHERE workspace_id=?", f.workspace);
      java.util.function.LongFunction<Callable<Boolean>> reserve =
          actor ->
              () -> {
                var input = f.fixture.input(3);
                input.setParentId(f.workspace);
                try {
                  f.fixture.service.initialize(input, actor, f.workspace);
                  return true;
                } catch (RPanException failure) {
                  if (failure.getCode() != 4101) throw failure;
                  return false;
                }
              };
      var requests =
          java.util.stream.LongStream.range(0, 20)
              .mapToObj(index -> reserve.apply(index % 2 + 1))
              .toList();
      var results = pool.invokeAll(requests);
      int success = 0;
      for (var result : results) if (result.get(5, TimeUnit.SECONDS)) success++;
      assertEquals(1, success);
      assertEquals(3, f.reserved());
    }
  }

  @Test
  public void readsDoNotWaitForExclusiveWorkspaceOrFileLocksAndRevocationStillApplies()
      throws Exception {
    org.junit.Assume.assumeTrue(
        "Read/write locking is verified on MySQL", Boolean.getBoolean("workspace.mysql.test"));
    try (var f = new TeamFixture();
        var pool = Executors.newFixedThreadPool(2)) {
      var db = f.fixture.database;
      long folder = db.tx.execute(status -> f.files.folder(f.workspace, 1, f.workspace, "Reports"));
      var input = f.fixture.input(3);
      input.setParentId(folder);
      input.setMd5("900150983cd24fb0d6963f7d28e17f72");
      String task = f.fixture.service.initialize(input, 1L, f.workspace).getUploadId();
      f.receive(task, 1);
      long file = f.completion.complete(task, 1L).getUserFileId();
      var entered = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      var writer =
          pool.submit(
              () ->
                  db.tx.executeWithoutResult(
                      status -> {
                        f.uploads.lock(f.workspace);
                        db.jdbc.queryForList(
                            "SELECT file_id FROM r_pan_user_file WHERE file_id IN (?,?) FOR UPDATE",
                            folder,
                            file);
                        entered.countDown();
                        try {
                          if (!release.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("Writer gate timed out");
                        } catch (InterruptedException error) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(error);
                        }
                      }));
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      try {
        var reader =
            pool.submit(
                () ->
                    db.tx.execute(
                        status -> {
                          assertEquals(1, f.files.list(f.workspace, 4, folder, false).size());
                          assertEquals(1, f.files.search(f.workspace, 4, "data").size());
                          assertEquals(file, f.files.downloadable(f.workspace, 4, file));
                          return true;
                        }));
        assertTrue(reader.get(3, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
      writer.get(3, TimeUnit.SECONDS);
      db.tx.executeWithoutResult(status -> f.governance.remove(f.workspace, 1, 4));
      assertThrows(
          RPanException.class,
          () -> db.tx.execute(status -> f.files.list(f.workspace, 4, folder, false)));
    }
  }

  @Test
  public void collectionArchivesMemberAndExternalSubmissionsAndRevocationBlocksFinalRegistration()
      throws Exception {
    try (var f = new TeamFixture()) {
      var db = f.fixture.database;
      var collections = new WorkspaceCollections(db.jdbc, f.files, f.uploads);
      String collection =
          db.tx.execute(
              status ->
                  collections.create(
                      f.workspace,
                      1,
                      f.workspace,
                      "Deliverables",
                      java.time.Instant.now().plusSeconds(3600),
                      100,
                      ".txt"));
      var managementView = db.tx.execute(status -> collections.detail(collection, 1));
      assertTrue(managementView.canManage());
      assertFalse(managementView.canSubmit());
      assertThrows(
          RPanException.class,
          () -> db.tx.execute(status -> collections.destination(collection, 1)));
      assertThrows(
          RPanException.class, () -> db.tx.execute(status -> collections.detail(collection, 3)));
      for (long actor : List.of(2L, 3L, 4L))
        db.tx.executeWithoutResult(
            status -> collections.invite(f.workspace, 1, collection, actor, true));
      assertTrue(db.tx.execute(status -> collections.detail(collection, 2)).canSubmit());
      assertTrue(db.tx.execute(status -> collections.detail(collection, 3)).canSubmit());
      assertFalse(db.tx.execute(status -> collections.detail(collection, 4)).canSubmit());
      assertFalse(db.tx.execute(status -> collections.detail(collection, 3)).canManage());
      for (long actor : List.of(2L, 3L)) {
        var task = f.collectionTask(collection, actor);
        var result = f.completion.complete(task, actor);
        assertEquals(result.getUserFileId(), f.completion.complete(task, actor).getUserFileId());
      }
      assertEquals(6, f.used());
      assertEquals(0, f.reserved());
      assertEquals(
          1, db.tx.execute(status -> collections.submissions(collection, 3, false)).size());
      assertEquals(2, db.tx.execute(status -> collections.submissions(collection, 1, true)).size());
      assertThrows(RPanException.class, () -> f.collectionTask(collection, 4));
      String pending = f.collectionTask(collection, 3);
      db.tx.executeWithoutResult(
          status -> collections.invite(f.workspace, 1, collection, 3, false));
      assertThrows(RPanException.class, () -> f.completion.complete(pending, 3L));
      db.tx.executeWithoutResult(status -> collections.invite(f.workspace, 1, collection, 3, true));
      assertThrows(RPanException.class, () -> f.completion.complete(pending, 3L));
      f.fixture.service.cancel(pending, 3L);
      assertEquals(0, f.reserved());
      db.tx.executeWithoutResult(status -> collections.close(f.workspace, 1, collection));
      assertFalse(db.tx.execute(status -> collections.detail(collection, 3)).canSubmit());
      assertTrue(db.tx.execute(status -> collections.detail(collection, 1)).canManage());
      db.tx.executeWithoutResult(status -> f.governance.remove(f.workspace, 1, 2));
      assertFalse(db.tx.execute(status -> collections.detail(collection, 2)).canSubmit());
    }
  }

  @Test
  public void collectionInvitationsAreAtomicDiscoverableAndScopedToRecipient() throws Exception {
    try (var f = new TeamFixture()) {
      var db = f.fixture.database;
      var collections = new WorkspaceCollections(db.jdbc, f.files, f.uploads);
      assertEquals(
          List.of(2L, 1L),
          db.tx.execute(status -> collections.recipients(f.workspace, 1, "")).stream()
              .map(WorkspaceCollections.Recipient::id)
              .toList());
      assertEquals(
          3L,
          db.tx
              .execute(status -> collections.recipients(f.workspace, 1, "external"))
              .getFirst()
              .id());
      assertTrue(
          db.tx.execute(status -> collections.recipients(f.workspace, 1, "exter")).isEmpty());
      assertThrows(
          RPanException.class,
          () -> db.tx.execute(status -> collections.recipients(f.workspace, 2, "")));
      String collection =
          db.tx.execute(
              status ->
                  collections.create(
                      f.workspace,
                      1,
                      f.workspace,
                      "Delivery invitation",
                      java.time.Instant.now().plusSeconds(3600),
                      100,
                      ".txt",
                      List.of(2L, 3L, 2L)));
      assertEquals(
          2, db.tx.execute(status -> collections.invitees(f.workspace, 1, collection)).size());
      assertEquals(collection, collections.received(2).getFirst().id());
      assertTrue(collections.received(3).getFirst().canSubmit());
      assertTrue(collections.received(4).isEmpty());
      assertTrue(collections.received(1).isEmpty());
      var task = f.collectionTask(collection, 3);
      f.completion.complete(task, 3L);
      assertEquals(1, collections.received(3).getFirst().submittedCount());
      db.tx.executeWithoutResult(
          status -> collections.invite(f.workspace, 1, collection, 2, false));
      assertTrue(collections.received(2).isEmpty());
      db.tx.executeWithoutResult(
          status -> collections.invite(f.workspace, 1, collection, 3, false));
      var historical = collections.received(3).getFirst();
      assertFalse(historical.invited());
      assertFalse(historical.canSubmit());
      assertFalse(db.tx.execute(status -> collections.detail(collection, 3)).canSubmit());
      assertThrows(
          RPanException.class, () -> db.tx.execute(status -> collections.detail(collection, 2)));
      assertEquals(1, historical.submittedCount());
      assertEquals(1, collections.submissions(collection, 3, false).size());
      assertThrows(
          RPanException.class,
          () -> db.tx.execute(status -> collections.invitees(f.workspace, 2, collection)));
    }
  }

  @Test
  public void collectionReportTracksPendingPeopleAndPreservesSourceAndOriginalFilenameAfterPurge()
      throws Exception {
    try (var f = new TeamFixture()) {
      var db = f.fixture.database;
      var collections = new WorkspaceCollections(db.jdbc, f.files, f.uploads);
      String collection =
          db.tx.execute(
              status ->
                  collections.create(
                      f.workspace,
                      1,
                      f.workspace,
                      "Reports",
                      java.time.Instant.now().plusSeconds(3600),
                      100,
                      ".txt",
                      List.of(2L, 3L)));
      String task = f.collectionTask(collection, 2);
      long file = f.completion.complete(task, 2L).getUserFileId();
      var input = f.fixture.input(3);
      input.setName("ordinary.txt");
      input.setParentId(f.workspace);
      input.setMd5("900150983cd24fb0d6963f7d28e17f72");
      String normal = f.fixture.service.initialize(input, 1L, f.workspace).getUploadId();
      f.receive(normal, 1);
      f.completion.complete(normal, 1L);
      var report = db.tx.execute(status -> collections.detail(collection, 1));
      assertEquals(2, report.invitedCount());
      assertEquals(1, report.submittedPeople());
      assertEquals(1, report.submittedFiles());
      var people = db.tx.execute(status -> collections.invitees(f.workspace, 1, collection));
      assertEquals(
          1,
          people.stream()
              .filter(person -> person.id() == 2)
              .findFirst()
              .orElseThrow()
              .submittedCount());
      assertEquals(
          0,
          people.stream()
              .filter(person -> person.id() == 3)
              .findFirst()
              .orElseThrow()
              .submittedCount());
      var nodes = db.tx.execute(status -> f.files.list(f.workspace, 1, f.workspace, false));
      var collected = nodes.stream().filter(node -> node.id() == file).findFirst().orElseThrow();
      assertEquals(collection, collected.collectionId());
      assertEquals("Reports", collected.collectionTitle());
      assertEquals("member", collected.creatorName());
      assertNull(
          nodes.stream()
              .filter(node -> node.name().equals("ordinary.txt"))
              .findFirst()
              .orElseThrow()
              .collectionId());
      db.tx.executeWithoutResult(
          status -> f.files.update(f.workspace, 1, file, "renamed.txt", null));
      assertEquals(
          collection,
          db.tx
              .execute(status -> f.files.search(f.workspace, 1, "renamed"))
              .getFirst()
              .collectionId());
      String migration;
      try (var stream =
          new ClassPathResource("sql/migrations/20261125_collection_reporting.sql")
              .getInputStream()) {
        migration = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      }
      String backfill =
          migration.substring(
              migration.indexOf("UPDATE "),
              migration.indexOf(';', migration.indexOf("UPDATE ")) + 1);
      db.jdbc.update(
          "UPDATE r_pan_workspace_submission SET filename=NULL,file_bytes=NULL WHERE upload_id=?",
          task);
      db.jdbc.execute(backfill);
      db.jdbc.execute(
          backfill); // Repeating the deploy reconciliation must not change an existing snapshot.

      var submission = collections.submissions(collection, 1, true).getFirst();
      assertEquals("answer.txt", submission.filename());
      assertEquals(Long.valueOf(3), submission.bytes());
      assertEquals("member", submission.username());
      assertTrue(submission.available());
      db.tx.executeWithoutResult(status -> f.files.recycle(f.workspace, 1, file, false, false));
      db.tx.executeWithoutResult(status -> f.files.recycle(f.workspace, 1, file, false, true));
      var historical = collections.submissions(collection, 2, false).getFirst();
      assertEquals("answer.txt", historical.filename());
      assertFalse(historical.available());
      assertEquals(1, db.tx.execute(status -> collections.detail(collection, 1)).submittedPeople());
      assertEquals(0, db.tx.execute(status -> collections.detail(collection, 3)).submittedFiles());
      assertThrows(
          RPanException.class,
          () -> db.tx.execute(status -> collections.invitees(f.workspace, 4, collection)));
    }
  }

  @Test
  public void invalidRecipientRollsBackCollectionAndAllInvitations() throws Exception {
    try (var f = new TeamFixture()) {
      var db = f.fixture.database;
      var collections = new WorkspaceCollections(db.jdbc, f.files, f.uploads);
      for (var recipients : List.of(List.of(2L, 999L), List.of(2L, 4L), List.<Long>of())) {
        assertThrows(
            RPanException.class,
            () ->
                db.tx.execute(
                    status ->
                        collections.create(
                            f.workspace,
                            1,
                            f.workspace,
                            "Invalid",
                            java.time.Instant.now().plusSeconds(3600),
                            100,
                            "",
                            recipients)));
        assertEquals(
            0,
            db.jdbc
                .queryForObject("SELECT COUNT(*) FROM r_pan_workspace_collection", Integer.class)
                .intValue());
        assertEquals(
            0,
            db.jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM r_pan_workspace_collection_invite", Integer.class)
                .intValue());
      }
    }
  }

  @Test
  public void sharingChecksRecipientAndRevokeSurvivesCreatorLeavingTeam() throws Exception {
    try (var f = new TeamFixture()) {
      var db = f.fixture.database;
      db.tx.executeWithoutResult(
          status -> f.governance.assign(f.workspace, 1, 2, WorkspaceRole.ADMIN));
      var task = f.fixture.input(3);
      task.setParentId(f.workspace);
      task.setMd5("900150983cd24fb0d6963f7d28e17f72");
      var upload = f.fixture.service.initialize(task, 2L, f.workspace);
      f.receive(upload.getUploadId(), 2);
      long file = f.completion.complete(upload.getUploadId(), 2L).getUserFileId();
      var shares = new WorkspaceSharing(db.jdbc, f.files, f.uploads);
      String share =
          db.tx.execute(
              status ->
                  shares.create(
                      f.workspace, 2, file, 3, java.time.Instant.now().plusSeconds(3600)));
      assertEquals(file, db.tx.execute(status -> shares.downloadable(share, 3)).longValue());
      assertThrows(
          RPanException.class, () -> db.tx.execute(status -> shares.downloadable(share, 4)));
      db.tx.executeWithoutResult(status -> f.governance.remove(f.workspace, 1, 2));
      db.tx.executeWithoutResult(status -> shares.revoke(f.workspace, 1, share));
      assertThrows(
          RPanException.class, () -> db.tx.execute(status -> shares.downloadable(share, 3)));
      assertEquals(3, f.used());
    }
  }

  @Test
  public void registrationWinningTheRaceRetainsTeamFileWhenMemberIsRemoved() throws Exception {
    org.junit.Assume.assumeTrue(
        "Commit-order locking is verified on MySQL", Boolean.getBoolean("workspace.mysql.test"));
    try (var f = new TeamFixture();
        var pool = Executors.newFixedThreadPool(2)) {
      var input = f.fixture.input(3);
      input.setParentId(f.workspace);
      input.setMd5("900150983cd24fb0d6963f7d28e17f72");
      String task = f.fixture.service.initialize(input, 2L, f.workspace).getUploadId();
      f.receive(task, 2);
      var entered = new java.util.concurrent.CountDownLatch(1);
      var finish = new java.util.concurrent.CountDownLatch(1);
      var real = f.fixture.database.session.getMapper(RPanFileMapper.class);
      var gate =
          org.mockito.Mockito.mock(
              RPanFileMapper.class, org.mockito.AdditionalAnswers.delegatesTo(real));
      org.mockito.Mockito.doAnswer(
              call -> {
                int inserted = real.insertSelective(call.getArgument(0));
                entered.countDown();
                if (!finish.await(5, TimeUnit.SECONDS))
                  throw new IllegalStateException("registration gate timed out");
                return inserted;
              })
          .when(gate)
          .insertSelective(org.mockito.ArgumentMatchers.any());
      ReflectionTestUtils.setField(f.completion, "files", gate);
      var completed = pool.submit(() -> f.completion.complete(task, 2L));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var removing =
          pool.submit(
              () ->
                  f.fixture.database.tx.executeWithoutResult(
                      status -> f.governance.remove(f.workspace, 1, 2)));
      try {
        assertThrows(
            java.util.concurrent.TimeoutException.class,
            () -> removing.get(150, TimeUnit.MILLISECONDS));
      } finally {
        finish.countDown();
      }
      completed.get(5, TimeUnit.SECONDS);
      removing.get(5, TimeUnit.SECONDS);
      assertEquals(3, f.used());
      assertTrue(f.governance.list(2).isEmpty());
    }
  }

  @Test
  public void removalWinningTheRaceBlocksRegistration() throws Exception {
    org.junit.Assume.assumeTrue(
        "Commit-order locking is verified on MySQL", Boolean.getBoolean("workspace.mysql.test"));
    try (var f = new TeamFixture();
        var pool = Executors.newFixedThreadPool(2)) {
      var input = f.fixture.input(3);
      input.setParentId(f.workspace);
      input.setMd5("900150983cd24fb0d6963f7d28e17f72");
      String task = f.fixture.service.initialize(input, 2L, f.workspace).getUploadId();
      f.receive(task, 2);
      var removed = new java.util.concurrent.CountDownLatch(1);
      var finish = new java.util.concurrent.CountDownLatch(1);
      var removing =
          pool.submit(
              () ->
                  f.fixture.database.tx.executeWithoutResult(
                      status -> {
                        f.governance.remove(f.workspace, 1, 2);
                        removed.countDown();
                        try {
                          if (!finish.await(5, TimeUnit.SECONDS))
                            throw new IllegalStateException("removal gate timed out");
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                      }));
      assertTrue(removed.await(5, TimeUnit.SECONDS));
      var completing = pool.submit(() -> f.completion.complete(task, 2L));
      try {
        assertThrows(
            java.util.concurrent.TimeoutException.class,
            () -> completing.get(150, TimeUnit.MILLISECONDS));
      } finally {
        finish.countDown();
      }
      removing.get(5, TimeUnit.SECONDS);
      var failure =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> completing.get(5, TimeUnit.SECONDS));
      assertTrue(failure.getCause() instanceof RPanException);
      assertEquals(0, f.used());
      f.fixture.service.cancel(task, 2L);
      assertEquals(0, f.reserved());
    }
  }

  @Test
  public void restoringTeamDirectoryKeepsPreviouslyTrashedChildrenInRecycleBin() throws Exception {
    try (var f = new TeamFixture()) {
      var tx = f.fixture.database.tx;
      long directory = tx.execute(status -> f.files.folder(f.workspace, 1, f.workspace, "handoff"));
      long activeChild = tx.execute(status -> f.files.folder(f.workspace, 1, directory, "active"));
      long priorTrash =
          tx.execute(status -> f.files.folder(f.workspace, 1, directory, "old-trash"));
      tx.executeWithoutResult(status -> f.files.recycle(f.workspace, 1, priorTrash, false, false));
      tx.executeWithoutResult(status -> f.files.recycle(f.workspace, 1, directory, false, false));
      tx.executeWithoutResult(status -> f.files.recycle(f.workspace, 1, directory, true, false));
      var live = tx.execute(status -> f.files.list(f.workspace, 1, directory, false));
      assertEquals(List.of(activeChild), live.stream().map(WorkspaceFiles.Node::id).toList());
      var trashed = tx.execute(status -> f.files.list(f.workspace, 1, directory, true));
      assertEquals(List.of(priorTrash), trashed.stream().map(WorkspaceFiles.Node::id).toList());
      long other = tx.execute(status -> f.governance.create(3, "other team")).id();
      assertThrows(
          RPanException.class,
          () -> tx.execute(status -> f.files.folder(f.workspace, 1, other, "invalid")));
      tx.executeWithoutResult(status -> f.files.recycle(f.workspace, 1, directory, false, false));
      tx.executeWithoutResult(status -> f.files.recycle(f.workspace, 1, directory, false, true));
      assertEquals(0, tx.execute(status -> f.files.list(f.workspace, 1, f.workspace, true)).size());
    }
  }

  private static final class TeamFixture implements AutoCloseable {
    final UploadTaskFixture fixture =
        new UploadTaskFixture(Boolean.getBoolean("workspace.mysql.test"));
    final WorkspaceUploads uploads;
    final WorkspaceService governance;
    final WorkspaceFiles files;
    final UploadCompletionService completion;
    final long workspace;
    final java.sql.Connection schemaConnection;

    TeamFixture() throws Exception {
      var db = fixture.database;
      db.jdbc.execute("CREATE TABLE r_pan_user(user_id BIGINT PRIMARY KEY, username VARCHAR(255))");
      db.jdbc.update(
          "INSERT INTO r_pan_user VALUES(1,'owner'),(2,'member'),(3,'external'),(4,'viewer')");
      schemaConnection = db.dataSource.getConnection();
      for (var script :
          List.of(
              "20261124_workspace_governance.sql",
              "20261125_workspace_files.sql",
              "20261125_workspace_sharing.sql",
              "20261125_workspace_collections.sql",
              "20261125_collection_reporting.sql")) {
        ScriptUtils.executeSqlScript(
            schemaConnection, new ClassPathResource("sql/migrations/" + script));
      }
      uploads = new WorkspaceUploads(db.jdbc);
      governance = new WorkspaceService(db.jdbc, 100);
      files = new WorkspaceFiles(db.jdbc, uploads, db.lifecycle);
      workspace = db.tx.execute(status -> governance.create(1, "Delivery")).id();
      db.tx.executeWithoutResult(
          status -> {
            governance.assign(workspace, 1, 2, WorkspaceRole.MEMBER);
            governance.assign(workspace, 1, 4, WorkspaceRole.VIEWER);
          });
      ReflectionTestUtils.setField(fixture.service, "team", uploads);
      ReflectionTestUtils.setField(db.space, "team", uploads);
      completion =
          new UploadCompletionService(
              fixture.tasks,
              db.session.getMapper(RPanFileMapper.class),
              db.session.getMapper(RPanUserFileMapper.class),
              fixture.storage,
              fixture.progress,
              fixture.locks,
              new DataSourceTransactionManager(db.dataSource),
              fixture.root.resolve("final").toString(),
              db.space,
              UploadTaskFixture.LOCAL_ONLY);
      ReflectionTestUtils.setField(completion, "team", uploads);
    }

    String collectionTask(String collection, long actor) {
      var input = fixture.input(3);
      input.setParentId(workspace);
      input.setName("answer.txt");
      input.setMd5("900150983cd24fb0d6963f7d28e17f72");
      var task = fixture.service.initialize(input, actor, workspace, collection);
      receive(task.getUploadId(), actor);
      return task.getUploadId();
    }

    void receive(String upload, long actor) {
      fixture.service.receive(upload, 0, new MockMultipartFile("file", new byte[] {97, 98}), actor);
      fixture.service.receive(upload, 1, new MockMultipartFile("file", new byte[] {99}), actor);
    }

    long used() {
      return fixture.database.jdbc.queryForObject(
          "SELECT used_bytes FROM r_pan_workspace WHERE workspace_id=?", Long.class, workspace);
    }

    long reserved() {
      return fixture.database.jdbc.queryForObject(
          "SELECT reserved_bytes FROM r_pan_workspace WHERE workspace_id=?", Long.class, workspace);
    }

    public void close() throws Exception {
      for (var table :
          List.of(
              "r_pan_workspace_submission",
              "r_pan_workspace_collection_invite",
              "r_pan_workspace_collection",
              "r_pan_workspace_share",
              "r_pan_workspace_reservation",
              "r_pan_workspace_member",
              "r_pan_workspace",
              "r_pan_user")) fixture.database.jdbc.execute("DROP TABLE " + table);
      schemaConnection.close();
      fixture.close();
    }
  }
}
