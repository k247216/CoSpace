package com.rubin.rpan.modules.file;

import com.rubin.rpan.modules.file.dao.*;
import com.rubin.rpan.modules.file.quota.config.UserSpaceProperties;
import com.rubin.rpan.modules.file.quota.service.UserSpaceService;
import com.rubin.rpan.modules.file.service.impl.*;
import java.io.InputStream;
import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.h2.jdbcx.JdbcDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class FileLifecycleFixture implements AutoCloseable {
  static final String MD5 = "0123456789abcdef0123456789abcdef";
  final DataSource dataSource;
  final JdbcTemplate jdbc;
  final TransactionTemplate tx;
  final SqlSessionTemplate session;
  final UserFileServiceImpl service;
  final FileServiceImpl files;
  final FileLifecycleService lifecycle;
  final UserSpaceService space;

  FileLifecycleFixture(boolean mysql) throws Exception {
    if (mysql) {
      String url = System.getProperty("cleanup.mysql.url");
      if (url == null
          || !url.matches(
              "jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/r_pan_cleanup_test_[a-zA-Z0-9]+(?:\\?.*)?")) {
        throw new IllegalArgumentException(
            "MySQL tests require a local disposable r_pan_cleanup_test_* database");
      }
      DriverManagerDataSource ds = new DriverManagerDataSource();
      ds.setUrl(url);
      ds.setUsername(System.getenv("RPAN_TEST_MYSQL_USER"));
      ds.setPassword(System.getenv("RPAN_TEST_MYSQL_PASSWORD"));
      dataSource = ds;
    } else {
      JdbcDataSource ds = new JdbcDataSource();
      ds.setURL(
          "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
      dataSource = ds;
    }
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute(
        "CREATE TABLE r_pan_file (file_id BIGINT PRIMARY KEY, filename VARCHAR(255), real_path"
            + " VARCHAR(700), file_size VARCHAR(255), file_size_desc VARCHAR(255), file_suffix"
            + " VARCHAR(255), file_preview_content_type VARCHAR(255), md5 VARCHAR(255), create_user"
            + " BIGINT, create_time TIMESTAMP)");
    jdbc.execute(
        "CREATE TABLE r_pan_user_file (file_id BIGINT PRIMARY KEY, user_id BIGINT, parent_id"
            + " BIGINT, real_file_id BIGINT DEFAULT 0, filename VARCHAR(255), folder_flag INT,"
            + " file_size_desc VARCHAR(255), file_type INT, del_flag INT DEFAULT 0, create_user"
            + " BIGINT, create_time TIMESTAMP NULL, update_user BIGINT, update_time TIMESTAMP"
            + " NULL)");
    jdbc.execute("CREATE INDEX idx_md5 ON r_pan_file(md5)");
    jdbc.execute(
        "CREATE INDEX idx_file_reference ON"
            + " r_pan_user_file(real_file_id,user_id,del_flag,folder_flag)");
    try (Connection c = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(
          c, new ClassPathResource("sql/migrations/20261112_file_cleanup_schema.sql"));
      ScriptUtils.executeSqlScript(
          c, new ClassPathResource("sql/migrations/20261123_file_storage_type.sql"));
      ScriptUtils.executeSqlScript(
          c, new ClassPathResource("sql/migrations/20261117_user_space_schema.sql"));
    }
    jdbc.update(
        "INSERT INTO"
            + " r_pan_file(file_id,filename,real_path,file_size,file_size_desc,file_suffix,file_preview_content_type,md5,create_user,create_time)"
            + " VALUES (900,'blob','/unused','10','10"
            + " B','.txt','text/plain',?,1,CURRENT_TIMESTAMP)",
        MD5);
    jdbc.update(
        "INSERT INTO r_pan_user_space(user_id,total_bytes) VALUES(1,10737418240),(2,10737418240)");
    reference(100, 1, 0, 0, 1, 0);
    reference(200, 2, 0, 0, 1, 0);
    reference(101, 1, 100, 900, 0, 0);
    Configuration configuration =
        new Configuration(
            new Environment("test", new SpringManagedTransactionFactory(), dataSource));
    for (String resource :
        new String[] {
          "mappers/file/RPanFileMapper.xml",
          "mappers/file/RPanUserFileMapper.xml",
          "mappers/file/RPanFileCleanupTaskMapper.xml",
          "mappers/file/UserSpaceMapper.xml"
        }) {
      try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
        new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
      }
    }
    session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
    lifecycle = new FileLifecycleService();
    ReflectionTestUtils.setField(
        lifecycle, "rPanFileMapper", session.getMapper(RPanFileMapper.class));
    ReflectionTestUtils.setField(
        lifecycle, "rPanUserFileMapper", session.getMapper(RPanUserFileMapper.class));
    ReflectionTestUtils.setField(
        lifecycle, "taskMapper", session.getMapper(RPanFileCleanupTaskMapper.class));
    ReflectionTestUtils.setField(
        lifecycle, "storageType", "com.rubin.rpan.storage.local.LocalStorageProcessor");
    files = new FileServiceImpl();
    ReflectionTestUtils.setField(files, "rPanFileMapper", session.getMapper(RPanFileMapper.class));
    ReflectionTestUtils.setField(files, "fileLifecycleService", lifecycle);
    space =
        new UserSpaceService(session.getMapper(UserSpaceMapper.class), new UserSpaceProperties());
    service = new UserFileServiceImpl();
    ReflectionTestUtils.setField(service, "space", space);
    ReflectionTestUtils.setField(
        service,
        "iShareService",
        org.mockito.Mockito.mock(com.rubin.rpan.modules.share.service.IShareService.class));
    ReflectionTestUtils.setField(service, "iFileService", files);
    ReflectionTestUtils.setField(
        service, "rPanUserFileMapper", session.getMapper(RPanUserFileMapper.class));
    ReflectionTestUtils.setField(service, "fileLifecycleService", lifecycle);
    tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    tx.setTimeout(10);
  }

  void reference(long id, long user, long parent, long real, int folder, int deleted) {
    long bytes =
        folder == 1
            ? 0
            : Long.parseLong(
                jdbc.queryForObject(
                    "SELECT file_size FROM r_pan_file WHERE file_id=?", String.class, real));
    jdbc.update(
        "INSERT INTO"
            + " r_pan_user_file(file_id,user_id,parent_id,real_file_id,filename,folder_flag,del_flag,space_bytes)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        id,
        user,
        parent,
        real,
        "entry-" + id,
        folder,
        deleted,
        bytes);
    jdbc.update("UPDATE r_pan_user_space SET used_bytes=used_bytes+? WHERE user_id=?", bytes, user);
  }

  String state() {
    return jdbc.queryForObject(
        "SELECT lifecycle_state FROM r_pan_file WHERE file_id=900", String.class);
  }

  int tasks() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM r_pan_file_cleanup_task", Integer.class);
  }

  @Override
  public void close() {
    jdbc.execute("DROP TABLE r_pan_upload_reservation");
    jdbc.execute("DROP TABLE r_pan_user_space");
    jdbc.execute("DROP TABLE r_pan_file_cleanup_task");
    jdbc.execute("DROP TABLE r_pan_user_file");
    jdbc.execute("DROP TABLE r_pan_file");
  }
}
