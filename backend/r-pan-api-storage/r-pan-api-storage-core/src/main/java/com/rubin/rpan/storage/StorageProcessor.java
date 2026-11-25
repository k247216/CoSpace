package com.rubin.rpan.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 存储处理器顶级接口 */
public interface StorageProcessor {

  /**
   * 文件存储
   *
   * @param inputStream 文件输入流
   * @param size 文件大小
   * @param name 文件原名
   * @return 文件存储路径
   * @throws IOException
   */
  String store(InputStream inputStream, Long size, String name) throws IOException;

  /**
   * 读取文件进输出流
   *
   * @param filePath 文件路径
   * @param outputStream 输出流
   * @throws IOException
   */
  void read2OutputStream(String filePath, OutputStream outputStream) throws IOException;

  /**
   * 删除文件
   *
   * @param filePath 文件路径
   * @throws IOException
   */
  void delete(String filePath) throws IOException;
}
