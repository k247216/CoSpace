package com.rubin.rpan.util.file.type.context;

import com.rubin.rpan.util.file.type.FileTypeDefiner;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

public final class FileTypeContext {

  private static final List<FileTypeDefiner> DEFINERS =
      ServiceLoader.load(FileTypeDefiner.class).stream()
          .map(ServiceLoader.Provider::get)
          .sorted(Comparator.comparingInt(FileTypeDefiner::getOrder).reversed())
          .toList();

  private FileTypeContext() {}

  public static Integer getFileTypeCode(String filename) {
    for (FileTypeDefiner definer : DEFINERS) {
      if (definer.isMatched(filename)) {
        return definer.getFileTypeCode();
      }
    }
    throw new IllegalStateException("获取文件类型失败");
  }
}
