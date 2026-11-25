package com.rubin.rpan.modules.file;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.rubin.rpan.modules.file.controller.UploadTaskController;
import com.rubin.rpan.modules.file.dao.RPanUserFileMapper;
import com.rubin.rpan.modules.file.upload.limit.*;
import com.rubin.rpan.modules.file.upload.service.*;
import com.rubin.rpan.util.UserIdUtil;
import java.util.*;
import org.junit.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

public class UploadRateLimitTest {
  @Test
  public void exhaustionAndDependencyFailureAreDistinct() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
        .thenReturn(Arrays.asList(0L, 1501L));
    UploadInitRateLimiter limiter =
        new UploadInitRateLimiter(redis, new UploadRateLimitProperties());
    try {
      limiter.check(1L);
      Assert.fail();
    } catch (UploadLimitException e) {
      Assert.assertFalse(e.isUnavailable());
      Assert.assertEquals(2, e.getRetryAfterSeconds());
    }
    when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("injected Redis outage"));
    try {
      limiter.check(1L);
      Assert.fail();
    } catch (UploadLimitException e) {
      Assert.assertTrue(e.isUnavailable());
    }
  }

  @Test
  public void disabledLimiterDoesNotTouchRedis() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    UploadRateLimitProperties p = new UploadRateLimitProperties();
    p.setEnabled(false);
    new UploadInitRateLimiter(redis, p).check(1L);
    verifyNoInteractions(redis);
  }

  @Test
  public void http429And503HappenBeforeTaskAndReservationCreation() throws Exception {
    try (UploadTaskFixture f = new UploadTaskFixture(false)) {
      UploadInitRateLimiter limiter = mock(UploadInitRateLimiter.class);
      UploadTaskService service =
          new UploadTaskService(
              f.tasks,
              f.database.session.getMapper(RPanUserFileMapper.class),
              f.progress,
              f.storage,
              f.locks,
              f.properties,
              new DataSourceTransactionManager(f.database.dataSource),
              UploadTaskFixture.LOCAL,
              f.database.space,
              limiter,
              UploadTaskFixture.LOCAL_ONLY);
      MockMvc mvc =
          MockMvcBuilders.standaloneSetup(
                  new UploadTaskController(service, mock(UploadCompletionService.class)))
              .setControllerAdvice(new UploadLimitExceptionHandler())
              .build();
      UserIdUtil.set(1L);
      try {
        doThrow(new UploadLimitException(false, 3, null)).when(limiter).check(1L);
        mvc.perform(post("/file/upload/tasks").contentType("application/json").content(input()))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "3"))
            .andExpect(jsonPath("$.status").value(4429));
        doThrow(new UploadLimitException(true, 1, null)).when(limiter).check(1L);
        mvc.perform(post("/file/upload/tasks").contentType("application/json").content(input()))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value(4503));
        Assert.assertEquals(
            0,
            f.database
                .jdbc
                .queryForObject("SELECT COUNT(*) FROM r_pan_upload_task", Integer.class)
                .intValue());
        Assert.assertEquals(0, f.database.space.view(1L).getReservedBytes());
      } finally {
        UserIdUtil.set(null);
      }
    }
  }

  private String input() {
    return "{\"name\":\"test.txt\",\"size\":3,\"md5\":\"5289df737df57326fcdd22597afb1fac\",\"parentId\":100}";
  }
}
