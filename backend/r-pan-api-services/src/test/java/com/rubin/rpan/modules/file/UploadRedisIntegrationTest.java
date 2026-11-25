package com.rubin.rpan.modules.file;

import com.rubin.rpan.modules.file.upload.concurrent.UploadLocks;
import com.rubin.rpan.modules.file.upload.service.UploadTaskService;
import com.rubin.rpan.modules.file.upload.storage.RedisUploadProgressStore;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;

public class UploadRedisIntegrationTest {
  private LettuceConnectionFactory factory;
  private StringRedisTemplate redis;
  private RedisUploadProgressStore store;
  private final List<String> ids = new ArrayList<>();

  @Before
  public void setUp() {
    Assume.assumeTrue(
        "Enable with -Dupload.redis.integration=true",
        Boolean.getBoolean("upload.redis.integration"));
    RedisStandaloneConfiguration config = RedisTestSupport.configuration();
    config.setDatabase(15);
    String password = System.getenv("RPAN_TEST_REDIS_PASSWORD");
    if (password != null && !password.isEmpty()) config.setPassword(password);
    factory = new LettuceConnectionFactory(config);
    factory.afterPropertiesSet();
    redis = new StringRedisTemplate(factory);
    store = new RedisUploadProgressStore(redis);
  }

  @After
  public void tearDown() {
    if (redis != null) for (String id : ids) redis.delete(RedisUploadProgressStore.key(id));
    if (factory != null) factory.destroy();
  }

  private String id() {
    String value = UUID.randomUUID().toString();
    ids.add(value);
    return value;
  }

  @Test
  public void luaMarkUsesRedisBitOrderAndDoesNotInflateDuplicates() {
    String id = id();
    store.mark(id, 0, 30000);
    store.mark(id, 7, 30000);
    store.mark(id, 8, 30000);
    store.mark(id, 0, 30000);
    byte[] raw = store.read(id);
    Assert.assertEquals(0x81, raw[0] & 255);
    Assert.assertEquals(0x80, raw[1] & 255);
    Assert.assertEquals(Arrays.asList(0, 7, 8), RedisUploadProgressStore.decode(raw, 9));
    Long ttl = redis.getExpire(RedisUploadProgressStore.key(id), TimeUnit.MILLISECONDS);
    Assert.assertTrue(ttl > 0 && ttl <= 30000);
  }

  @Test
  public void replaceRemovesStaleBitsAndEmptySnapshotDeletesKey() {
    String id = id();
    store.mark(id, 0, 30000);
    store.replace(id, Arrays.asList(2, 9), 30000);
    Assert.assertEquals(Arrays.asList(2, 9), RedisUploadProgressStore.decode(store.read(id), 10));
    store.replace(id, Collections.emptyList(), 30000);
    Assert.assertNull(store.read(id));
  }

  @Test
  public void diskAndRedisProgressRecoverAfterOnlyOwnBitmapIsLost() throws Exception {
    try (UploadTaskFixture fixture = new UploadTaskFixture(false)) {
      UploadTaskService service = fixture.newService(store, new UploadLocks());
      String id = service.initialize(fixture.input(5), 1L).getUploadId();
      ids.add(id);
      service.receive(id, 0, new MockMultipartFile("file", new byte[] {1, 2}), 1L);
      service.receive(id, 2, new MockMultipartFile("file", new byte[] {5}), 1L);
      Assert.assertEquals(Arrays.asList(0, 2), service.status(id, 1L).getUploadedChunks());
      redis.delete(RedisUploadProgressStore.key(id));
      service = fixture.newService(store, new UploadLocks());
      Assert.assertEquals(Arrays.asList(0, 2), service.status(id, 1L).getUploadedChunks());
      Assert.assertEquals(Arrays.asList(0, 2), RedisUploadProgressStore.decode(store.read(id), 3));
    }
  }
}
