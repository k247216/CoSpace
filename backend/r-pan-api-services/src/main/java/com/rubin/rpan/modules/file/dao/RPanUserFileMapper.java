package com.rubin.rpan.modules.file.dao;

import com.rubin.rpan.modules.file.entity.RPanUserFile;
import com.rubin.rpan.modules.file.vo.RPanUserFileVO;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

/** 用户文件数据层 */
@Repository(value = "rPanUserFileMapper")
public interface RPanUserFileMapper {

  int deleteByPrimaryKey(Long fileId);

  int moveOwnedLiveFile(
      @Param("record") RPanUserFile record, @Param("previousParentId") Long previousParentId);

  int insert(RPanUserFile record);

  int insertSelective(RPanUserFile record);

  RPanUserFile selectByPrimaryKey(Long fileId);

  int updateByPrimaryKeySelective(RPanUserFile record);

  int updateByPrimaryKey(RPanUserFile record);

  List<RPanUserFileVO> selectRPanUserFileVOListByUserIdAndFileTypeAndParentIdAndDelFlag(
      @Param("userId") Long userId,
      @Param("fileTypeArray") List<Integer> fileTypeArray,
      @Param("parentId") Long parentId,
      @Param("delFlag") Integer delFlag);

  RPanUserFile selectByFileIdAndUserId(@Param("fileId") Long fileId, @Param("userId") Long userId);

  int selectCountByUserIdAndFilenameAndParentId(
      @Param("userId") Long userId,
      @Param("filename") String filename,
      @Param("parentId") Long parentId);

  int deleteBatch(@Param("idList") List<Long> idList, @Param("userId") Long userId);

  List<RPanUserFile> selectFolderListByUserId(@Param("userId") Long userId);

  List<RPanUserFile> selectListByFileIdList(@Param("idList") List<Long> idList);

  int insertBatch(@Param("recordList") List<RPanUserFile> recordList);

  List<RPanUserFileVO> selectRPanUserFileVOListByUserIdAndFilenameAndFileTypes(
      @Param("userId") Long userId,
      @Param("keyword") String keyword,
      @Param("fileTypeArray") List<Integer> fileTypeArray);

  RPanUserFileVO selectRPanUserFileVOByFileIdAndUserId(
      @Param("fileId") Long fileId, @Param("userId") Long userId);

  List<RPanUserFile> selectAllListByParentId(@Param("parentId") Long parentId);

  List<RPanUserFile> selectAvailableListByParentId(@Param("parentId") Long parentId);

  List<RPanUserFileVO> selectAvailableRPanUserFileVOListByParentId(
      @Param("parentId") Long parentId);

  int updateUserFileDelFlagByFileIdListAndUserId(
      @Param("fileIdList") List<Long> fileIdList, @Param("userId") Long userId);

  int physicalDeleteBatch(@Param("fileIdList") List<Long> fileIdList, @Param("userId") Long userId);

  int selectCountByRealFileId(@Param("realFileId") Long realFileId);

  List<RPanUserFileVO> selectRPanUserFileVOListByFileIdList(
      @Param("fileIdList") List<Long> fileIdList);

  RPanUserFile selectTopFolderByUserId(@Param("userId") Long userId);

  List<Long> selectAvailableFileIdListByParentId(@Param("parentId") Long parentId);

  RPanUserFile selectByIdForUpdate(@Param("fileId") Long fileId);

  List<Long> selectReferenceIdsForUpdate(@Param("realFileId") Long realFileId);

  List<Long> selectLiveReferenceIdsForUpdate(
      @Param("realFileId") Long realFileId, @Param("userId") Long userId);
}
