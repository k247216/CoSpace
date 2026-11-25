package com.rubin.rpan.modules.workspace;

import static org.junit.Assert.*;

import com.rubin.rpan.common.exception.RPanException;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

public class WorkspaceGovernanceTest {
  @Test
  public void rolesAndWorkspaceBoundariesProtectGovernance() throws Exception {
    try (var fixture = new Fixture()) {
      var owner = fixture.tx.execute(status -> fixture.service.create(1, "team"));
      long id = owner.id();
      fixture.tx.executeWithoutResult(
          status -> {
            fixture.service.assign(id, 1, 2, WorkspaceRole.ADMIN);
            fixture.service.assign(id, 2, 3, WorkspaceRole.MEMBER);
            fixture.service.assign(id, 2, 4, WorkspaceRole.VIEWER);
          });
      assertEquals(1, fixture.service.list(4).size());
      assertTrue(fixture.service.list(5).isEmpty());
      assertThrows(
          RPanException.class, () -> fixture.tx.execute(status -> fixture.service.members(id, 5)));
      assertThrows(
          RPanException.class, () -> fixture.tx.execute(status -> fixture.service.members(id, 4)));
      assertThrows(
          RPanException.class,
          () ->
              fixture.tx.execute(status -> fixture.service.assign(id, 2, 3, WorkspaceRole.ADMIN)));
      assertThrows(
          RPanException.class,
          () ->
              fixture.tx.execute(status -> fixture.service.assign(id, 2, 1, WorkspaceRole.VIEWER)));
      assertThrows(
          RPanException.class,
          () ->
              fixture.tx.execute(status -> fixture.service.assign(id, 1, 3, WorkspaceRole.OWNER)));
      fixture.tx.executeWithoutResult(status -> fixture.service.remove(id, 2, 3));
      assertTrue(fixture.service.list(3).isEmpty());
      var rejoined =
          fixture.tx.execute(status -> fixture.service.assign(id, 1, 3, WorkspaceRole.MEMBER));
      assertEquals(3, rejoined.generation());
      var repeated =
          fixture.tx.execute(status -> fixture.service.assign(id, 1, 3, WorkspaceRole.MEMBER));
      assertEquals(rejoined, repeated);
      assertEquals(4, fixture.tx.execute(status -> fixture.service.members(id, 1)).size());
    }
  }

  @Test
  public void simultaneousInvitationCreatesOneMembershipAndFailedCreationRollsBack()
      throws Exception {
    try (var fixture = new Fixture();
        var pool = Executors.newFixedThreadPool(2)) {
      long id = fixture.tx.execute(status -> fixture.service.create(1, "team")).id();
      Callable<WorkspaceService.Member> assign =
          () ->
              fixture.tx.execute(status -> fixture.service.assign(id, 1, 2, WorkspaceRole.MEMBER));
      var first = pool.submit(assign);
      var second = pool.submit(assign);
      assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
      assertEquals(
          1,
          fixture
              .jdbc
              .queryForObject(
                  "SELECT COUNT(*) FROM r_pan_workspace_member WHERE workspace_id=? AND user_id=2",
                  Integer.class,
                  id)
              .intValue());
      assertThrows(
          IllegalStateException.class,
          () ->
              fixture.tx.executeWithoutResult(
                  status -> {
                    fixture.service.create(1, "rollback");
                    throw new IllegalStateException("simulated commit failure");
                  }));
      assertEquals(1, fixture.service.list(1).size());
    }
  }

  private static final class Fixture implements AutoCloseable {
    final JdbcDataSource ds = new JdbcDataSource();
    final JdbcTemplate jdbc;
    final WorkspaceService service;
    final TransactionTemplate tx;
    final java.sql.Connection schemaConnection;

    Fixture() throws Exception {
      ds.setURL(
          "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=4000");
      jdbc = new JdbcTemplate(ds);
      jdbc.execute("CREATE TABLE r_pan_user(user_id BIGINT PRIMARY KEY)");
      jdbc.update("INSERT INTO r_pan_user VALUES (1),(2),(3),(4),(5)");
      schemaConnection = ds.getConnection();
      ScriptUtils.executeSqlScript(
          schemaConnection,
          new ClassPathResource("sql/migrations/20261124_workspace_governance.sql"));
      service = new WorkspaceService(jdbc, 1024);
      tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    public void close() throws Exception {
      jdbc.execute("DROP ALL OBJECTS");
      schemaConnection.close();
    }
  }
}
