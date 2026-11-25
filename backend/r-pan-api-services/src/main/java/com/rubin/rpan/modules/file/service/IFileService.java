package com.rubin.rpan.modules.file.service;

import com.rubin.rpan.modules.file.entity.RPanFile;
import java.util.List;

/** 实体文件业务处理接口 */
public interface IFileService {

  void delete(String fileIds);

  RPanFile getFileDetail(Long realFileId);

  List<RPanFile> getFileListByMd5(String md5);

  RPanFile findReusableFile(String md5, Long size, Long userId);
}
