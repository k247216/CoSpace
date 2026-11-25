package com.rubin.rpan.modules.file;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rubin.rpan.common.exception.RPanExceptionHandler;
import com.rubin.rpan.modules.file.controller.FileRestController;
import com.rubin.rpan.modules.file.controller.UploadTaskController;
import com.rubin.rpan.modules.file.dao.RPanFileMapper;
import com.rubin.rpan.modules.file.dao.RPanUserFileMapper;
import com.rubin.rpan.modules.file.upload.service.UploadCompletionService;
import com.rubin.rpan.util.UserIdUtil;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

public class UploadApiContractTest {
  private UploadTaskFixture fixture;
  private MockMvc mvc;

  @Before
  public void setUp() throws Exception {
    fixture = new UploadTaskFixture(false);
    UploadCompletionService completion =
        new UploadCompletionService(
            fixture.tasks,
            fixture.database.session.getMapper(RPanFileMapper.class),
            fixture.database.session.getMapper(RPanUserFileMapper.class),
            fixture.storage,
            fixture.progress,
            fixture.locks,
            new DataSourceTransactionManager(fixture.database.dataSource),
            fixture.root.resolve("final").toString(),
            fixture.database.space,
            UploadTaskFixture.LOCAL_ONLY);
    FileRestController files = new FileRestController();
    ReflectionTestUtils.setField(files, "iUserFileService", fixture.database.service);
    mvc =
        MockMvcBuilders.standaloneSetup(
                files, new UploadTaskController(fixture.service, completion))
            .build();
    UserIdUtil.set(1L);
  }

  @After
  public void tearDown() throws Exception {
    UserIdUtil.set(null);
    if (fixture != null) fixture.close();
  }

  @Test
  public void legacyOrdinaryAndChunkUploadsHaveNoRoute() throws Exception {
    mvc.perform(
            multipart("/file/upload")
                .file(
                    new MockMultipartFile("file", "test.txt", "text/plain", new byte[] {1, 2, 3})))
        .andExpect(status().isNotFound());
    mvc.perform(
            multipart("/file/upload")
                .file(new MockMultipartFile("file", "test.txt", "text/plain", new byte[] {1, 2}))
                .param("chunks", "2")
                .param("chunk", "0")
                .param("size", "3"))
        .andExpect(status().isNotFound());
    Assert.assertEquals(
        0,
        fixture
            .database
            .jdbc
            .queryForObject("SELECT COUNT(*) FROM r_pan_upload_task", Integer.class)
            .intValue());
  }

  @Test
  public void completionFailureDoesNotExposeDatabaseDetails() throws Exception {
    UploadCompletionService completion = mock(UploadCompletionService.class);
    doThrow(new DataAccessResourceFailureException("private physical file SQL"))
        .when(completion)
        .complete("failed-task", 1L);
    MockMvc errorMvc =
        MockMvcBuilders.standaloneSetup(new UploadTaskController(fixture.service, completion))
            .setControllerAdvice(new RPanExceptionHandler())
            .build();
    errorMvc
        .perform(post("/file/upload/tasks/failed-task/complete"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(1))
        .andExpect(jsonPath("$.message").value("服务暂时不可用，请重试"));
  }

  @Test
  public void taskProtocolStillPublishesActiveFileAndReference() throws Exception {
    MvcResult initialized =
        mvc.perform(
                post("/file/upload/tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\"test.txt\",\"size\":3,\"md5\":\"5289df737df57326fcdd22597afb1fac\",\"parentId\":100}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value("UPLOADING"))
            .andReturn();
    String id =
        new ObjectMapper()
            .readTree(initialized.getResponse().getContentAsString())
            .path("data")
            .path("uploadId")
            .asText();
    mvc.perform(
            multipart("/file/upload/tasks/" + id + "/chunks/0")
                .file(new MockMultipartFile("file", new byte[] {1, 2})))
        .andExpect(status().isOk());
    mvc.perform(
            multipart("/file/upload/tasks/" + id + "/chunks/1")
                .file(new MockMultipartFile("file", new byte[] {3})))
        .andExpect(status().isOk());
    MvcResult completed =
        mvc.perform(post("/file/upload/tasks/" + id + "/complete"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value("COMPLETED"))
            .andReturn();
    var result =
        new ObjectMapper().readTree(completed.getResponse().getContentAsString()).path("data");
    var task = fixture.tasks.selectById(id);
    Assert.assertEquals(task.getUserFileId().toString(), result.path("userFileId").textValue());
    Assert.assertEquals(task.getRealFileId().toString(), result.path("realFileId").textValue());
    // 完成响应重试仍然只有一份新文件、一条新引用。
    mvc.perform(post("/file/upload/tasks/" + id + "/complete"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.state").value("COMPLETED"));
    Assert.assertEquals(
        2,
        fixture
            .database
            .jdbc
            .queryForObject(
                "SELECT COUNT(*) FROM r_pan_file WHERE lifecycle_state='ACTIVE'", Integer.class)
            .intValue());
    Assert.assertEquals(
        4,
        fixture
            .database
            .jdbc
            .queryForObject("SELECT COUNT(*) FROM r_pan_user_file", Integer.class)
            .intValue());
    Assert.assertEquals(
        UploadTaskFixture.LOCAL,
        fixture.database.jdbc.queryForObject(
            "SELECT storage_type FROM r_pan_file WHERE file_id=?",
            String.class,
            fixture.tasks.selectById(id).getRealFileId()));
    Assert.assertArrayEquals(
        new byte[] {1, 2, 3},
        Files.readAllBytes(Paths.get(fixture.tasks.selectById(id).getFinalPath())));
  }
}
