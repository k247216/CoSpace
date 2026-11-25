package com.rubin.rpan.modules.file.service.impl;

import com.rubin.rpan.common.exception.RPanException;
import com.rubin.rpan.modules.file.constant.FileLifecycleState;
import com.rubin.rpan.modules.file.dao.RPanFileMapper;
import com.rubin.rpan.modules.file.entity.RPanFile;
import com.rubin.rpan.modules.file.service.IFileService;
import com.rubin.rpan.util.StringListUtil;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 物理文件业务处理类 */
@Service(value = "fileService")
@Transactional(rollbackFor = Exception.class)
public class FileServiceImpl implements IFileService {

  @Autowired
  @Qualifier(value = "rPanFileMapper")
  private RPanFileMapper rPanFileMapper;

  @Autowired private FileLifecycleService fileLifecycleService;

  /**
   * 为无引用的物理文件安排异步清理，不在此删除存储内容。
   *
   * @param fileIds
   * @return
   */
  @Override
  public void delete(String fileIds) {
    if (StringUtils.isBlank(fileIds)) {
      throw new RPanException("文件id不能为空");
    }
    List<Long> ids = StringListUtil.string2LongList(fileIds);
    fileLifecycleService.scheduleIfUnreferenced(ids);
  }

  /**
   * 获取实体文件详情
   *
   * @param realFileId
   * @return
   */
  @Override
  public RPanFile getFileDetail(Long realFileId) {
    RPanFile rPanFile = rPanFileMapper.selectByPrimaryKey(realFileId);
    if (Objects.isNull(rPanFile) || rPanFile.getLifecycleState() != FileLifecycleState.ACTIVE) {
      throw new RPanException("实体文件不存在");
    }
    return rPanFile;
  }

  @Override
  public List<RPanFile> getFileListByMd5(String md5) {
    return rPanFileMapper.selectByMd5(md5);
  }

  @Override
  public RPanFile findReusableFile(String md5, Long size, Long userId) {
    return rPanFileMapper.selectReusableFile(md5, String.valueOf(size), userId);
  }
}
