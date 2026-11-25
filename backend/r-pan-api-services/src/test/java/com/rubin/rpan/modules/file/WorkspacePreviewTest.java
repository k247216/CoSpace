package com.rubin.rpan.modules.file;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rubin.rpan.storage.StorageManager;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

public class WorkspacePreviewTest {
  @Test
  public void previewKeepsNosniffHeaderAndRendersActiveContentAsPlainText() throws Exception {
    try (var fixture = new FileLifecycleFixture(false)) {
      var storage = mock(StorageManager.class);
      byte[] content = "<script>test</script>".getBytes(StandardCharsets.UTF_8);
      doAnswer(
              call -> {
                call.<OutputStream>getArgument(2).write(content);
                return null;
              })
          .when(storage)
          .readFile(nullable(String.class), eq("/unused"), any(OutputStream.class));
      ReflectionTestUtils.setField(fixture.service, "storageManager", storage);
      for (String mime : new String[] {"text/plain", "text/html", "image/svg+xml"}) {
        fixture.jdbc.update(
            "UPDATE r_pan_file SET file_preview_content_type=? WHERE file_id=900", mime);
        var response = new MockHttpServletResponse();
        fixture.service.previewAuthorized(101L, response);
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertTrue(response.getContentType().startsWith("text/plain"));
        assertArrayEquals(content, response.getContentAsByteArray());
      }
    }
  }
}
