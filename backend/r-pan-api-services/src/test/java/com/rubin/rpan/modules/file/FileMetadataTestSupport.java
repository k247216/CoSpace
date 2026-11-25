package com.rubin.rpan.modules.file;

import com.rubin.rpan.modules.file.constant.FileLifecycleState;
import com.rubin.rpan.modules.file.dao.RPanFileMapper;
import com.rubin.rpan.modules.file.dao.RPanUserFileMapper;
import com.rubin.rpan.modules.file.entity.RPanFile;
import com.rubin.rpan.modules.file.entity.RPanUserFile;
import com.rubin.rpan.util.IdGenerator;
import com.rubin.rpan.util.file.type.context.FileTypeContext;
import java.util.Date;
import org.junit.Assert;

final class FileMetadataTestSupport {
  private FileMetadataTestSupport() {}

  static RPanFile insertPhysical(RPanFileMapper files, Long userId) {
    RPanFile file = new RPanFile();
    file.setFileId(IdGenerator.nextId());
    file.setFilename("test.txt");
    file.setRealPath("/unused-test-content/" + file.getFileId());
    file.setFileSize("3");
    file.setFileSizeDesc("0.00K");
    file.setFileSuffix(".txt");
    file.setFilePreviewContentType("text/plain");
    file.setMd5("5289df737df57326fcdd22597afb1fac");
    file.setLifecycleState(FileLifecycleState.ACTIVE);
    file.setCreateUser(userId);
    file.setCreateTime(new Date());
    Assert.assertEquals(1, files.insertSelective(file));
    return file;
  }

  static void insertReference(
      RPanFileMapper files,
      RPanUserFileMapper references,
      Long parentId,
      Long userId,
      com.rubin.rpan.modules.file.quota.service.UserSpaceService space) {
    space.lock(userId);
    RPanFile file = insertPhysical(files, userId);
    RPanUserFile reference = new RPanUserFile();
    reference.setFileId(IdGenerator.nextId());
    reference.setUserId(userId);
    reference.setParentId(parentId);
    reference.setRealFileId(file.getFileId());
    reference.setFilename("test.txt");
    reference.setFolderFlag(0);
    reference.setDelFlag(0);
    reference.setFileType(FileTypeContext.getFileTypeCode("test.txt"));
    reference.setFileSizeDesc(file.getFileSizeDesc());
    reference.setSpaceBytes(3L);
    reference.setCreateUser(userId);
    reference.setUpdateUser(userId);
    reference.setCreateTime(new Date());
    reference.setUpdateTime(new Date());
    space.charge(userId, 3L);
    Assert.assertEquals(1, references.insertSelective(reference));
  }
}
