package com.rubin.rpan.modules.file.dao;

import com.rubin.rpan.modules.file.entity.RPanFile;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

/** 物理文件数据层 */
@Repository(value = "rPanFileMapper")
public interface RPanFileMapper {

  int countByRealPath(@Param("realPath") String realPath);

  int deleteByPrimaryKey(Long fileId);

  int insert(RPanFile record);

  int insertSelective(RPanFile record);

  RPanFile selectByPrimaryKey(Long fileId);

  int updateByPrimaryKeySelective(RPanFile record);

  int updateByPrimaryKey(RPanFile record);

  List<RPanFile> selectByFileIdList(@Param("fileIdList") List<String> fileIdList);

  int deleteBatch(@Param("fileIdList") List<String> fileIdList);

  List<RPanFile> selectByMd5(@Param("md5") String md5);

  RPanFile selectReusableFile(
      @Param("md5") String md5, @Param("size") String size, @Param("userId") Long userId);

  RPanFile selectByIdForUpdate(@Param("fileId") Long fileId);

  int markDeleting(@Param("fileId") Long fileId);

  int markDeleted(@Param("fileId") Long fileId);
}
