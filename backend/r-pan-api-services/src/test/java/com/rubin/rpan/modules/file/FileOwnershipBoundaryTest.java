package com.rubin.rpan.modules.file;

import com.rubin.rpan.common.config.RPanConfig;
import com.rubin.rpan.common.exception.RPanException;
import com.rubin.rpan.modules.share.dao.*;
import com.rubin.rpan.modules.share.service.impl.*;
import com.rubin.rpan.modules.user.service.IUserService;
import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.*;
import org.springframework.test.util.ReflectionTestUtils;

public class FileOwnershipBoundaryTest {
  protected FileLifecycleFixture f;
  private ShareServiceImpl shares;

  protected boolean mysql() {
    return false;
  }

  @Before
  public void setUp() throws Exception {
    f = new FileLifecycleFixture(mysql());
    f.reference(201, 2, 200, 900, 0, 0);
    f.reference(300, 1, 100, 0, 1, 0);
    f.jdbc.execute(
        "CREATE TABLE r_pan_share(share_id BIGINT PRIMARY KEY,share_name VARCHAR(255),share_type"
            + " INT,share_day_type INT,share_day INT,share_end_time TIMESTAMP,share_url"
            + " VARCHAR(255),share_code VARCHAR(255),share_status INT,create_user"
            + " BIGINT,create_time TIMESTAMP)");
    f.jdbc.execute(
        "CREATE TABLE r_pan_share_file(id BIGINT AUTO_INCREMENT PRIMARY KEY,share_id BIGINT,file_id"
            + " BIGINT,create_user BIGINT,create_time TIMESTAMP,UNIQUE(share_id,file_id))");
    Configuration config = f.session.getConfiguration();
    for (String resource :
        new String[] {
          "mappers/share/RPanShareMapper.xml", "mappers/share/RPanShareFileMapper.xml"
        }) {
      try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
        new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
      }
    }
    ShareFileServiceImpl links = new ShareFileServiceImpl();
    ReflectionTestUtils.setField(
        links, "rPanShareFileMapper", f.session.getMapper(RPanShareFileMapper.class));
    ReflectionTestUtils.setField(links, "iUserFileService", f.service);
    shares = new ShareServiceImpl();
    ReflectionTestUtils.setField(
        shares, "rPanShareMapper", f.session.getMapper(RPanShareMapper.class));
    ReflectionTestUtils.setField(shares, "iShareFileService", links);
    ReflectionTestUtils.setField(shares, "iUserFileService", f.service);
    ReflectionTestUtils.setField(shares, "rPanConfig", new RPanConfig());
    ReflectionTestUtils.setField(
        shares, "iUserService", org.mockito.Mockito.mock(IUserService.class));
  }

  @After
  public void tearDown() {
    if (f != null) {
      f.jdbc.execute("DROP TABLE IF EXISTS r_pan_share_file");
      f.jdbc.execute("DROP TABLE IF EXISTS r_pan_share");
      f.close();
    }
  }

  private void reject(Runnable operation) {
    try {
      f.tx.execute(
          tx -> {
            operation.run();
            return null;
          });
      Assert.fail("Expected ownership rejection");
    } catch (RPanException expected) {
    }
  }

  private long parent(long id) {
    return f.jdbc.queryForObject(
        "SELECT parent_id FROM r_pan_user_file WHERE file_id=?", Long.class, id);
  }

  private void share(String ids) {
    shares.create("ownership-check", 0, 1, ids, 1L);
  }

  private void noShareRows() {
    Assert.assertEquals(
        0, f.jdbc.queryForObject("SELECT COUNT(*) FROM r_pan_share", Integer.class).intValue());
    Assert.assertEquals(
        0,
        f.jdbc.queryForObject("SELECT COUNT(*) FROM r_pan_share_file", Integer.class).intValue());
  }

  @Test
  public void cannotMoveOtherUsersFile() {
    reject(() -> f.service.transfer("201", 100L, 300L, 1L));
    Assert.assertEquals(200, parent(201));
  }

  @Test
  public void mixedMoveBatchDoesNotMoveAnyFile() {
    reject(() -> f.service.transfer("101__,__201", 100L, 300L, 1L));
    Assert.assertEquals(100, parent(101));
    Assert.assertEquals(200, parent(201));
  }

  @Test
  public void validMovePreservesOwnerAndQuota() {
    f.tx.execute(
        tx -> {
          f.service.transfer("101", 100L, 300L, 1L);
          return null;
        });
    Assert.assertEquals(300, parent(101));
    Assert.assertEquals(
        1,
        f.jdbc
            .queryForObject("SELECT update_user FROM r_pan_user_file WHERE file_id=101", Long.class)
            .longValue());
    Assert.assertEquals(
        1,
        f.jdbc
            .queryForObject("SELECT user_id FROM r_pan_user_file WHERE file_id=101", Long.class)
            .longValue());
    Assert.assertEquals(
        10,
        f.jdbc
            .queryForObject("SELECT used_bytes FROM r_pan_user_space WHERE user_id=1", Long.class)
            .longValue());
  }

  @Test
  public void cannotMoveRecycledSource() {
    f.jdbc.update("UPDATE r_pan_user_file SET del_flag=1 WHERE file_id=101");
    reject(() -> f.service.transfer("101", 100L, 300L, 1L));
    Assert.assertEquals(100, parent(101));
  }

  @Test
  public void cannotCreateShareOfOtherUsersFile() {
    reject(() -> share("201"));
    noShareRows();
  }

  @Test
  public void mixedShareBatchRollsBackEntireShare() {
    reject(() -> share("101__,__201"));
    noShareRows();
  }

  @Test
  public void cannotShareRecycledOrMissingSource() {
    f.jdbc.update("UPDATE r_pan_user_file SET del_flag=1 WHERE file_id=101");
    reject(() -> share("101"));
    reject(() -> share("999999"));
    noShareRows();
  }

  @Test
  public void deletedAncestorBlocksMoveAndShare() {
    f.reference(301, 1, 100, 0, 1, 1);
    f.reference(302, 1, 301, 900, 0, 0);
    reject(() -> f.service.transfer("302", 100L, 300L, 1L));
    reject(() -> share("302"));
    Assert.assertEquals(301, parent(302));
    noShareRows();
  }

  @Test
  public void foreignAncestorAndInvalidTargetAreRejected() {
    f.reference(301, 1, 200, 0, 1, 0);
    reject(() -> f.service.transfer("101", 100L, 301L, 1L));
    reject(() -> share("301"));
    Assert.assertEquals(100, parent(101));
    noShareRows();
  }

  @Test
  public void rootDirectoryCannotBeMoved() {
    reject(() -> f.service.transfer("100", 0L, 300L, 1L));
    Assert.assertEquals(0, parent(100));
  }

  @Test
  public void scopedUpdateCannotModifyForeignFileEvenWithoutServiceGuard() {
    com.rubin.rpan.modules.file.entity.RPanUserFile record =
        new com.rubin.rpan.modules.file.entity.RPanUserFile();
    record.setFileId(201L);
    record.setUserId(1L);
    record.setParentId(300L);
    record.setFilename("forged");
    record.setUpdateUser(1L);
    record.setUpdateTime(new java.util.Date());
    int changed =
        f.tx.execute(
            tx ->
                f.session
                    .getMapper(com.rubin.rpan.modules.file.dao.RPanUserFileMapper.class)
                    .moveOwnedLiveFile(record, 200L));
    Assert.assertEquals(0, changed);
    Assert.assertEquals(200, parent(201));
  }

  @Test
  public void validShareCanListOnlyAuthorizedFile() {
    Long id = f.tx.execute(tx -> shares.create("ownership-check", 0, 1, "101", 1L).getShareId());
    Assert.assertEquals(
        1, f.tx.execute(tx -> shares.detail(id).getrPanUserFileVOList().size()).intValue());
    Assert.assertEquals(
        101,
        f.jdbc
            .queryForObject("SELECT file_id FROM r_pan_share_file WHERE share_id=?", Long.class, id)
            .longValue());
  }
}
