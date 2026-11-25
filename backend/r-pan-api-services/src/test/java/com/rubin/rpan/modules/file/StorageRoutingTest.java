package com.rubin.rpan.modules.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.rubin.rpan.storage.StorageManager;
import com.rubin.rpan.storage.StorageProcessor;
import com.rubin.rpan.storage.StorageProcessorSelector;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.junit.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

public class StorageRoutingTest {
  @Test
  public void persistedAndLegacyFilesCanUseDifferentBackends() {
    new ApplicationContextRunner()
        .withBean(StorageProcessorSelector.class)
        .withBean(StorageManager.class)
        .withBean(LegacyBackend.class)
        .withBean(CurrentBackend.class)
        .withPropertyValues(
            "rpan.storage.processor.type=" + CurrentBackend.class.getName(),
            "rpan.storage.legacy-type=" + LegacyBackend.class.getName())
        .run(
            context -> {
              StorageManager manager = context.getBean(StorageManager.class);
              assertEquals("current:report.txt", read(manager, CurrentBackend.class.getName()));
              assertEquals("legacy:report.txt", read(manager, null));
              assertThrows(IOException.class, () -> read(manager, "unknown"));
            });
  }

  private static String read(StorageManager manager, String type) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    manager.readFile(type, "report.txt", output);
    return output.toString(java.nio.charset.StandardCharsets.UTF_8);
  }

  private static class LegacyBackend implements StorageProcessor {
    @Override
    public void read2OutputStream(String locator, OutputStream output) throws IOException {
      output.write(("legacy:" + locator).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public String store(InputStream input, Long size, String name) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(String locator) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class CurrentBackend extends LegacyBackend {
    @Override
    public void read2OutputStream(String locator, OutputStream output) throws IOException {
      output.write(("current:" + locator).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
  }
}
