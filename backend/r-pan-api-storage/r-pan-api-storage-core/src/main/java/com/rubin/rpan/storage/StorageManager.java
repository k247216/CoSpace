package com.rubin.rpan.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** 存储管理器 */
@Component(value = "storageManager")
public class StorageManager implements StorageProcessor {

  @Autowired
  @Qualifier(value = "storageProcessorSelector")
  private StorageProcessorSelector storageProcessorSelector;

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
    return storageProcessorSelector.select().store(inputStream, size, name);
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
    storageProcessorSelector.select().read2OutputStream(filePath, outputStream);
  }

  public void readFile(String storageType, String locator, OutputStream outputStream)
      throws IOException {
    storageProcessorSelector.selectForFile(storageType).read2OutputStream(locator, outputStream);
  }

  /**
   * 删除文件
   *
   * @param filePath 文件路径
   * @throws IOException
   */
  @Override
  public void delete(String filePath) throws IOException {
    storageProcessorSelector.select().delete(filePath);
  }
}
