package com.rubin.rpan.modules.file;

import java.sql.*;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

public class CleanupSchemaTest {
  private Connection connection;

  @Before
  public void setUp() throws Exception {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
    connection = ds.getConnection();
    execute("CREATE TABLE r_pan_file(file_id BIGINT PRIMARY KEY)");
    execute("INSERT INTO r_pan_file VALUES (900)");
    ScriptUtils.executeSqlScript(
        connection, new ClassPathResource("sql/migrations/20261112_file_cleanup_schema.sql"));
  }

  @After
  public void tearDown() throws Exception {
    if (connection != null) connection.close();
  }

  @Test
  public void legacyAndNewFilesDefaultToActive() throws Exception {
    Assert.assertEquals(
        "ACTIVE", scalar("SELECT lifecycle_state FROM r_pan_file WHERE file_id=900"));
    execute("INSERT INTO r_pan_file(file_id) VALUES (901)");
    Assert.assertEquals(
        "ACTIVE", scalar("SELECT lifecycle_state FROM r_pan_file WHERE file_id=901"));
  }

  @Test
  public void rollbackRevertsFileStateAndTaskTogether() throws Exception {
    connection.setAutoCommit(false);
    execute("UPDATE r_pan_file SET lifecycle_state='DELETING' WHERE file_id=900");
    insertTask(501, 900);
    connection.rollback();
    Assert.assertEquals(
        "ACTIVE", scalar("SELECT lifecycle_state FROM r_pan_file WHERE file_id=900"));
    Assert.assertEquals("0", scalar("SELECT COUNT(*) FROM r_pan_file_cleanup_task"));
  }

  @Test
  public void committedTaskHasIndependentDeliveryAndExecutionState() throws Exception {
    connection.setAutoCommit(false);
    execute("UPDATE r_pan_file SET lifecycle_state='DELETING' WHERE file_id=900");
    insertTask(501, 900);
    connection.commit();
    Assert.assertEquals(
        "DELETING", scalar("SELECT lifecycle_state FROM r_pan_file WHERE file_id=900"));
    Assert.assertEquals("PENDING", scalar("SELECT delivery_state FROM r_pan_file_cleanup_task"));
    Assert.assertEquals("PENDING", scalar("SELECT execution_state FROM r_pan_file_cleanup_task"));
    Assert.assertEquals("0", scalar("SELECT execution_attempts FROM r_pan_file_cleanup_task"));
    Assert.assertEquals("0", scalar("SELECT delivery_attempts FROM r_pan_file_cleanup_task"));
  }

  @Test
  public void oneCleanupTaskPerPhysicalFile() throws Exception {
    insertTask(501, 900);
    try {
      insertTask(502, 900);
      Assert.fail("Duplicate physical file task must be rejected");
    } catch (SQLException expected) {
      Assert.assertEquals("23505", expected.getSQLState());
    }
  }

  private void insertTask(long task, long file) throws Exception {
    execute(
        "INSERT INTO r_pan_file_cleanup_task(task_id,real_file_id,storage_type,storage_locator) "
            + "VALUES ("
            + task
            + ","
            + file
            + ",'LOCAL','/unused/test.txt')");
  }

  private void execute(String sql) throws Exception {
    try (Statement s = connection.createStatement()) {
      s.execute(sql);
    }
  }

  private String scalar(String sql) throws Exception {
    try (Statement s = connection.createStatement();
        ResultSet r = s.executeQuery(sql)) {
      Assert.assertTrue(r.next());
      return r.getString(1);
    }
  }
}
