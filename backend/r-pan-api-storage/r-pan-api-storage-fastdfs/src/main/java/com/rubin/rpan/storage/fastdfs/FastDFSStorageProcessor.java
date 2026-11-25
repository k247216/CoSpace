package com.rubin.rpan.storage.fastdfs;

import com.github.tobato.fastdfs.domain.StorePath;
import com.github.tobato.fastdfs.proto.storage.DownloadByteArray;
import com.github.tobato.fastdfs.service.FastFileStorageClient;
import com.rubin.rpan.storage.StorageProcessor;
import com.rubin.rpan.util.FileUtil;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component(value = "fastDFSStorageProcessor")
@ConditionalOnProperty(
    prefix = "rpan.storage.processor",
    name = "type",
    havingValue = "com.rubin.rpan.storage.fastdfs.FastDFSStorageProcessor")
public class FastDFSStorageProcessor implements StorageProcessor {

  private static final Integer ZERO_INT = 0;
  private static final Integer ONE_INT = 1;
  private static final String SLASH = "/";

  @Autowired private FastFileStorageClient fastFileStorageClient;

  @Value("${rpan.storage.fdfs.group:group1}")
  private String group;

  /**
   * 文件存储
   *
   * @param inputStream 文件输入流
   * @param size 文件大小
   * @param name 文件原名
   * @return 文件存储路径
   * @throws IOException
   */
  @Override
  public String store(InputStream inputStream, Long size, String name) throws IOException {
    StorePath storePath =
        fastFileStorageClient.uploadFile(group, inputStream, size, FileUtil.getFileExtName(name));
    return storePath.getFullPath();
  }

  /**
   * 读取文件进输出流
   *
   * @param filePath 文件路径
   * @param outputStream 输出流
   * @throws IOException
   */
  @Override
  public void read2OutputStream(String filePath, OutputStream outputStream) throws IOException {
    String group = filePath.substring(ZERO_INT, filePath.indexOf(SLASH));
    String path = filePath.substring(filePath.indexOf(SLASH) + ONE_INT);
    DownloadByteArray downloadByteArray = new DownloadByteArray();
    byte[] bytes = fastFileStorageClient.downloadFile(group, path, downloadByteArray);
    outputStream.write(bytes);
    outputStream.flush();
    outputStream.close();
  }

  /**
   * 删除文件
   *
   * @param filePath 文件路径
   * @throws IOException
   */
  @Override
  public void delete(String filePath) throws IOException {
    fastFileStorageClient.deleteFile(filePath);
  }
}
