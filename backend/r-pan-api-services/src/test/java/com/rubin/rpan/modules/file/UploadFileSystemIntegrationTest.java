package com.rubin.rpan.modules.file;

import com.rubin.rpan.modules.file.entity.RPanUploadTask;
import com.rubin.rpan.modules.file.upload.config.UploadProperties;
import com.rubin.rpan.modules.file.upload.storage.UploadChunkStorage;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.springframework.mock.web.MockMultipartFile;

public class UploadFileSystemIntegrationTest {
  @Test
  public void completeChunkCanBeAtomicallyPublishedOnProjectVolume() throws Exception {
    String configured = System.getProperty("upload.fs.root");
    Assume.assumeTrue(configured != null);
    Path root = Paths.get(configured).toAbsolutePath().normalize();
    Assert.assertEquals("upload-fs-check", root.getFileName().toString());
    Files.createDirectories(root);
    String id = UUID.randomUUID().toString();
    Path owned = root.resolve(id);
    try {
      UploadProperties p = new UploadProperties();
      p.setStagingPath(root.toString());
      UploadChunkStorage storage = new UploadChunkStorage(p);
      RPanUploadTask task = new RPanUploadTask();
      task.setUploadId(id);
      task.setFileSize(3L);
      task.setChunkSize(3);
      task.setTotalChunks(1);
      MockMultipartFile file = new MockMultipartFile("file", new byte[] {1, 2, 3});
      Assert.assertFalse(storage.receive(task, 0, file).getDuplicate());
      Assert.assertTrue(storage.receive(task, 0, file).getDuplicate());
      Assert.assertArrayEquals(
          new byte[] {1, 2, 3}, Files.readAllBytes(owned.resolve("chunks/0.part")));
      StringBuilder md5 = new StringBuilder();
      for (byte b : java.security.MessageDigest.getInstance("MD5").digest(new byte[] {1, 2, 3}))
        md5.append(String.format("%02x", b & 255));
      task.setExpectedMd5(md5.toString());
      task.setFinalPath(owned.resolve("final.bin").toString());
      storage.assemble(task);
      storage.assemble(task); // 正式文件已存在时校验复用。
      Assert.assertArrayEquals(
          new byte[] {1, 2, 3}, Files.readAllBytes(Paths.get(task.getFinalPath())));
    } finally {
      if (Files.exists(owned))
        try (java.util.stream.Stream<Path> paths = Files.walk(owned)) {
          for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
            Files.deleteIfExists(path);
        }
    }
  }
}
