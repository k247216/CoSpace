package com.rubin.rpan.storage.local;

import com.rubin.rpan.storage.StorageProcessor;
import com.rubin.rpan.storage.local.config.LocalStorageConfig;
import com.rubin.rpan.util.FileUtil;
import java.io.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component(value = "localStorageProcessor")
@ConditionalOnProperty(
    prefix = "rpan.storage.processor",
    name = "type",
    havingValue = "com.rubin.rpan.storage.local.LocalStorageProcessor")
public class LocalStorageProcessor implements StorageProcessor {

  @Autowired
  @Qualifier(value = "localStorageConfig")
  private LocalStorageConfig localStorageConfig;

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
    String filePath = FileUtil.generateFilePath(localStorageConfig.getRootFilePath(), name);
    File targetFile = new File(filePath);
    if (!targetFile.getParentFile().exists()) {
      targetFile.getParentFile().mkdirs();
    }
    targetFile.createNewFile();
    FileUtil.writeStreamToFile(inputStream, new FileOutputStream(targetFile), size);
    return filePath;
  }

  /**
   * 读取文件为输入流
   *
   * @param filePath 文件路径
   * @param outputStream 输出流
   * @throws IOException
   */
  @Override
  public void read2OutputStream(String filePath, OutputStream outputStream) throws IOException {
    File file = new File(filePath);
    FileUtil.writeFileToStream(new FileInputStream(file), outputStream, file.length());
  }

  /**
   * 删除文件
   *
   * @param filePath 文件路径
   * @throws IOException
   */
  @Override
  public void delete(String filePath) throws IOException {
    FileUtil.delete(new File(filePath));
  }
}
