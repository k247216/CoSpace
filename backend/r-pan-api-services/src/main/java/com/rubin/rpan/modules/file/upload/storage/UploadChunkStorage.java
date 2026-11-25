package com.rubin.rpan.modules.file.upload.storage;

import com.rubin.rpan.common.exception.RPanException;
import com.rubin.rpan.modules.file.entity.RPanUploadTask;
import com.rubin.rpan.modules.file.upload.config.UploadProperties;
import com.rubin.rpan.modules.file.upload.model.ChunkReceipt;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** 只处理字节与完整分片发布，不访问Redis或业务数据库。调用方须持同片锁。 */
@Component
public class UploadChunkStorage {
  private final UploadProperties properties;

  public UploadChunkStorage(UploadProperties properties) {
    this.properties = properties;
  }

  public static void requireId(String id) {
    if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
      throw new RPanException("上传任务ID不合法");
  }

  public static long expectedSize(RPanUploadTask task, int index) {
    if (index < 0 || index >= task.getTotalChunks()) throw new RPanException("分片编号越界");
    long offset = (long) index * task.getChunkSize();
    return Math.min(task.getChunkSize(), task.getFileSize() - offset);
  }

  public ChunkReceipt receive(RPanUploadTask task, int index, MultipartFile file)
      throws IOException {
    long expected = expectedSize(task, index);
    if (file == null || file.getSize() != expected) throw new RPanException("分片大小不匹配");
    // 先为这次请求创建独立临时文件
    Path directory = directory(task.getUploadId(), true);
    Path incoming = childDirectory(directory, "incoming", true),
        chunks = childDirectory(directory, "chunks", true);
    Path temporary = Files.createTempFile(incoming, "request-", ".tmp");
    try {
      MessageDigest digest = sha256();
      long bytes = 0;
      try (InputStream in = file.getInputStream();
          FileOutputStream out = new FileOutputStream(temporary.toFile())) {
        byte[] buffer = new byte[65536];
        int n;
        // 再打开输入流和输出流，边读边写
        while ((n = in.read(buffer)) != -1) {
          if (n == 0) continue;
          if (bytes + n > expected) throw new RPanException("分片实际大小超出预期");
          out.write(buffer, 0, n);
          digest.update(buffer, 0, n);
          bytes += n;
        }
        // 把字节写入本次临时文件。
        // 统计实际长度，拒绝过长或不足的内容。
        // 计算这片内容的 SHA-256，供重复上传时比较。
        if (bytes != expected) throw new RPanException("分片实际大小不足");
        out.getChannel().force(true);
      }
      String hash = hex(digest.digest());
      Path target = chunks.resolve(index + ".part");
      boolean duplicate = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
      // 0.part 不存在  将本次临时文件原子移动为 0.part
      // 0.part 已存在  检查已有分片的长度和摘要
      // 相同：复用已有分片
      // 不同：拒绝覆盖，返回失败
      if (duplicate) {
        verifyComplete(task, index, target);
        if (!hash.equals(hash(target))) throw new RPanException("同一分片编号的内容冲突，已有内容未覆盖");
      } else {
        // 同一文件系统原子发布；不支持时明确失败，不把半成品伪装成完整分片。
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
      }
      ChunkReceipt receipt = new ChunkReceipt();
      receipt.setUploadId(task.getUploadId());
      receipt.setChunkIndex(index);
      receipt.setSize(bytes);
      receipt.setSha256(hash);
      receipt.setDuplicate(duplicate);
      return receipt;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public boolean existsComplete(RPanUploadTask task, int index) throws IOException {
    Path chunks = childDirectory(directory(task.getUploadId(), false), "chunks", false);
    Path path = chunks.resolve(index + ".part");
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
    verifyComplete(task, index, path);
    return true;
  }

  public List<Integer> listComplete(RPanUploadTask task) throws IOException {
    Path chunks = childDirectory(directory(task.getUploadId(), false), "chunks", false);
    if (!Files.exists(chunks)) return Collections.emptyList();
    List<Integer> result = new ArrayList<>();
    try (DirectoryStream<Path> paths = Files.newDirectoryStream(chunks, "*.part")) {
      for (Path path : paths) {
        String name = path.getFileName().toString();
        if (!name.matches("(?:0|[1-9][0-9]{0,8})\\.part"))
          throw new IOException("Invalid completed chunk filename");
        int index = Integer.parseInt(name.substring(0, name.length() - 5));
        verifyComplete(task, index, path);
        result.add(index);
      }
    }
    Collections.sort(result);
    return result;
  }

  public void assemble(RPanUploadTask task) throws IOException {
    assembleTo(task, Paths.get(task.getFinalPath()));
  }

  public Path assembleForObjectStorage(RPanUploadTask task) throws IOException {
    Path target = directory(task.getUploadId(), true).resolve("verified-object");
    assembleTo(task, target);
    return target;
  }

  private void assembleTo(RPanUploadTask task, Path target) throws IOException {
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      verifyFile(task, target);
      return;
    }
    Path temporary = Files.createTempFile(target.getParent(), task.getUploadId() + "-", ".merging");
    try {
      Path chunks = childDirectory(directory(task.getUploadId(), false), "chunks", false);
      try (FileOutputStream out = new FileOutputStream(temporary.toFile())) {
        byte[] buffer = new byte[65536];
        for (int index = 0; index < task.getTotalChunks(); index++) {
          Path part = chunks.resolve(index + ".part");
          verifyComplete(task, index, part);
          try (InputStream in = Files.newInputStream(part)) {
            int n;
            while ((n = in.read(buffer)) != -1) if (n > 0) out.write(buffer, 0, n);
          }
        }
        out.getChannel().force(true);
      }
      verifyFile(task, temporary);
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private void verifyFile(RPanUploadTask task, Path file) throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file))
      throw new IOException("Invalid final file");
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    long size = 0;
    try (InputStream in = Files.newInputStream(file)) {
      byte[] buffer = new byte[65536];
      int n;
      while ((n = in.read(buffer)) != -1)
        if (n > 0) {
          digest.update(buffer, 0, n);
          size += n;
        }
    }
    if (size != task.getFileSize() || !hex(digest.digest()).equals(task.getExpectedMd5()))
      throw new IntegrityException("完整文件大小或MD5不匹配，请重新创建上传任务");
  }

  // 整个目录删除不是原子的：删了两片后第三片报错，前两片不会恢复。下次重新遍历，继续删除剩下的即可。
  // 调用方负责保证任务已经不能继续接收分片，并且持有任务写锁，避免一边删除、一边写入。
  // 删除的是当前任务的整个暂存目录
  public void removeChunks(RPanUploadTask task) throws IOException {
    // 定位当前任务的暂存目录。`false` 表示只查路径，不主动创建目录。
    Path directory = directory(task.getUploadId(), false);
    // 目录已经不存在，直接成功返回。重复清理不会因为“已经删过”而失败，这是幂等处理的一部分。
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
    // 遍历目录中的文件、子目录和根目录。
    // 不跟随符号链接；此目录只属于当前UUID任务。
    try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
      for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
        Files.deleteIfExists(p);
    }
  }

  public static class IntegrityException extends IOException {
    public IntegrityException(String message) {
      super(message);
    }
  }

  private void verifyComplete(RPanUploadTask task, int index, Path file) throws IOException {
    long size = expectedSize(task, index);
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(file)
        || Files.size(file) != size)
      throw new IOException("Completed chunk is invalid; do not report it as uploaded");
  }

  private Path directory(String id, boolean create) throws IOException {
    requireId(id);
    String configured = properties.getStagingPath();
    if (configured == null || configured.trim().isEmpty())
      throw new IOException("Upload staging directory is not configured");
    Path root = Paths.get(configured).toAbsolutePath().normalize();
    if (create) Files.createDirectories(root);
    if (Files.exists(root)) root = root.toRealPath();
    return childDirectory(root, id, create);
  }

  private Path childDirectory(Path parent, String name, boolean create) throws IOException {
    Path path = parent.resolve(name);
    if (create) {
      try {
        Files.createDirectory(path);
      } catch (FileAlreadyExistsException exists) {
      }
    }
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        && (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)))
      throw new IOException("Invalid upload directory");
    return path;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hash(Path path) throws IOException {
    MessageDigest digest = sha256();
    try (InputStream in = Files.newInputStream(path)) {
      byte[] b = new byte[65536];
      int n;
      while ((n = in.read(b)) != -1) if (n > 0) digest.update(b, 0, n);
    }
    return hex(digest.digest());
  }

  private static String hex(byte[] bytes) {
    char[] chars = new char[bytes.length * 2];
    char[] alphabet = "0123456789abcdef".toCharArray();
    for (int i = 0; i < bytes.length; i++) {
      int n = bytes[i] & 255;
      chars[i * 2] = alphabet[n >>> 4];
      chars[i * 2 + 1] = alphabet[n & 15];
    }
    return new String(chars);
  }
}
