package com.rubin.rpan.modules.file;

import com.rubin.rpan.common.exception.RPanException;
import com.rubin.rpan.modules.file.po.FileSecUploadPO;
import com.rubin.rpan.modules.file.service.impl.UserFileServiceImpl;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.sql.*;
import org.junit.*;

public class SecUploadSecurityTest {
  private static final String MD5 = "0123456789abcdef0123456789abcdef";
  private FileLifecycleFixture fixture;
  private org.springframework.transaction.TransactionStatus transaction;
  private Connection connection;
  private UserFileServiceImpl service;

  @Before
  public void setUp() throws Exception {
    fixture = new FileLifecycleFixture(false);
    transaction =
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(fixture.dataSource)
            .getTransaction(
                new org.springframework.transaction.support.DefaultTransactionDefinition());
    connection =
        org.springframework.jdbc.datasource.DataSourceUtils.getConnection(fixture.dataSource);
    service = fixture.service;
  }

  @After
  public void tearDown() throws Exception {
    if (fixture != null) {
      if (transaction != null)
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(fixture.dataSource)
            .rollback(transaction);
      fixture.close();
    }
  }

  @Test
  public void unrelatedUserCannotReuseKnownDigest() throws Exception {
    Assert.assertFalse(service.secUpload(200L, "copy.txt", MD5, 10L, 2L));
    Assert.assertEquals(3, count("r_pan_user_file"));
  }

  @Test
  public void ownerCanReuseWithoutCreatingPhysicalFile() throws Exception {
    Assert.assertTrue(service.secUpload(100L, "copy.txt", MD5, 10L, 1L));
    Assert.assertEquals(1, count("r_pan_file"));
    Assert.assertEquals(4, count("r_pan_user_file"));
    try (Statement s = connection.createStatement();
        ResultSet r = s.executeQuery("SELECT * FROM r_pan_user_file WHERE filename='copy.txt'")) {
      Assert.assertTrue(r.next());
      Assert.assertEquals(900, r.getLong("real_file_id"));
      Assert.assertEquals(100, r.getLong("parent_id"));
      Assert.assertNotEquals(101, r.getLong("file_id"));
    }
  }

  @Test
  public void legitimateRecipientCanReuseEvenIfNotPhysicalCreator() throws Exception {
    reference(201, 2, 200, 900, 0, 0);
    Assert.assertTrue(service.secUpload(200L, "copy.txt", MD5, 10L, 2L));
  }

  @Test
  public void creatorWithoutReferenceCannotReuse() throws Exception {
    sql("DELETE FROM r_pan_user_file WHERE file_id=101");
    reference(201, 2, 200, 900, 0, 0);
    Assert.assertFalse(service.secUpload(100L, "copy.txt", MD5, 10L, 1L));
  }

  @Test
  public void recycledReferenceDoesNotAuthorizeReuse() throws Exception {
    sql("UPDATE r_pan_user_file SET del_flag=1 WHERE file_id=101");
    Assert.assertFalse(service.secUpload(100L, "copy.txt", MD5, 10L, 1L));
  }

  @Test
  public void sizeMismatchDoesNotAuthorizeReuse() {
    Assert.assertFalse(service.secUpload(100L, "copy.txt", MD5, 11L, 1L));
  }

  @Test
  public void duplicatePhysicalRowsDoNotDisableAuthorizedReuse() throws Exception {
    sql(
        "INSERT INTO"
            + " r_pan_file(file_id,filename,real_path,storage_type,file_size,file_size_desc,file_suffix,file_preview_content_type,md5,create_user,create_time,lifecycle_state)"
            + " SELECT"
            + " 901,filename,real_path,storage_type,file_size,file_size_desc,file_suffix,file_preview_content_type,md5,2,create_time,lifecycle_state"
            + " FROM r_pan_file WHERE file_id=900");
    Assert.assertTrue(service.secUpload(100L, "copy.txt", MD5, 10L, 1L));
  }

  @Test
  public void rejectsInvalidTargetsAndAncestors() throws Exception {
    rejectTarget(200); // another user's directory
    rejectTarget(101); // a file
    rejectTarget(999); // missing
    rejectTarget(0); // virtual root is not an upload destination
    reference(102, 1, 100, 0, 1, 1);
    rejectTarget(102);
    reference(103, 1, 102, 0, 1, 0);
    rejectTarget(103); // ancestor in recycle bin
    reference(104, 1, 104, 0, 1, 0);
    rejectTarget(104); // malformed directory cycle
    Assert.assertEquals(1, count("r_pan_file"));
  }

  @Test
  public void requestRequiresValidDigestAndNonnegativeSize() {
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      FileSecUploadPO po = new FileSecUploadPO();
      po.setName("copy.txt");
      po.setParentId(100L);
      po.setMd5(MD5);
      Assert.assertFalse(factory.getValidator().validate(po).isEmpty());
      po.setSize(-1L);
      Assert.assertFalse(factory.getValidator().validate(po).isEmpty());
      po.setSize(0L);
      po.setMd5("invalid");
      Assert.assertFalse(factory.getValidator().validate(po).isEmpty());
      po.setMd5(MD5);
      Assert.assertTrue(factory.getValidator().validate(po).isEmpty());
    }
  }

  private void rejectTarget(long id) {
    try {
      service.secUpload(id, "copy.txt", MD5, 10L, 1L);
      Assert.fail("Expected invalid target to be rejected: " + id);
    } catch (RPanException expected) {
    }
  }

  private void reference(long id, long user, long parent, long real, int folder, int deleted)
      throws Exception {
    sql(
        "INSERT INTO"
            + " r_pan_user_file(file_id,user_id,parent_id,real_file_id,filename,folder_flag,del_flag)"
            + " VALUES ("
            + id
            + ","
            + user
            + ","
            + parent
            + ","
            + real
            + ",'entry-"
            + id
            + "',"
            + folder
            + ","
            + deleted
            + ")");
  }

  private void sql(String sql) throws Exception {
    try (Statement s = connection.createStatement()) {
      s.execute(sql);
    }
  }

  private int count(String table) throws Exception {
    try (Statement s = connection.createStatement();
        ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
      r.next();
      return r.getInt(1);
    }
  }
}
