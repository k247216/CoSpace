package com.rubin.rpan.modules.file;

import com.rubin.rpan.modules.file.cleanup.storage.LocalCleanupStorageDeleter;
import com.rubin.rpan.modules.file.cleanup.storage.UnsafeCleanupException;
import com.rubin.rpan.modules.file.entity.RPanFileCleanupTask;
import java.nio.file.*;
import org.junit.*;

public class LocalCleanupStorageTest {
  private Path root;
  private static final String TYPE = "com.rubin.rpan.storage.local.LocalStorageProcessor";

  @Before
  public void setUp() throws Exception {
    root = Files.createTempDirectory("rpan-storage-");
  }

  @After
  public void tearDown() throws Exception {
    Files.deleteIfExists(root);
  }

  private RPanFileCleanupTask task(Path path) {
    RPanFileCleanupTask t = new RPanFileCleanupTask();
    t.setStorageType(TYPE);
    t.setStorageLocator(path.toString());
    return t;
  }

  @Test
  public void deletesFileAndRepeatedDeleteSucceeds() throws Exception {
    Path path = Files.write(root.resolve("file.txt"), new byte[] {1});
    LocalCleanupStorageDeleter d = new LocalCleanupStorageDeleter(TYPE, root.toString());
    d.delete(task(path));
    d.delete(task(path));
    Assert.assertFalse(Files.exists(path));
  }

  @Test
  public void outsideRootIsNeverDeleted() throws Exception {
    Path outside = Files.createTempFile("outside-", ".txt");
    try {
      reject(new LocalCleanupStorageDeleter(TYPE, root.toString()), task(outside));
      Assert.assertTrue(Files.exists(outside));
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  public void storageTypeMismatchIsRejected() throws Exception {
    RPanFileCleanupTask t = task(root.resolve("missing"));
    t.setStorageType("OSS");
    reject(new LocalCleanupStorageDeleter(TYPE, root.toString()), t);
  }

  @Test
  public void symlinkCannotLeadToAnotherDirectory() throws Exception {
    Path outside = Files.createTempDirectory("outside-dir-");
    Path content = Files.write(outside.resolve("file.txt"), new byte[] {1});
    Path link = root.resolve("link");
    try {
      Files.createSymbolicLink(link, outside);
      reject(new LocalCleanupStorageDeleter(TYPE, root.toString()), task(link.resolve("file.txt")));
      Assert.assertTrue(Files.exists(content));
    } finally {
      Files.deleteIfExists(link);
      Files.deleteIfExists(content);
      Files.deleteIfExists(outside);
    }
  }

  @Test
  public void directoryIsNotAFileCleanupTarget() throws Exception {
    Path dir = Files.createDirectory(root.resolve("directory"));
    try {
      reject(new LocalCleanupStorageDeleter(TYPE, root.toString()), task(dir));
      Assert.assertTrue(Files.exists(dir));
    } finally {
      Files.deleteIfExists(dir);
    }
  }

  private void reject(LocalCleanupStorageDeleter d, RPanFileCleanupTask t) throws Exception {
    try {
      d.delete(t);
      Assert.fail("Expected unsafe task to be rejected");
    } catch (UnsafeCleanupException expected) {
    }
  }
}
