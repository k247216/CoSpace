package com.rubin.rpan.modules.file.dao;

import com.rubin.rpan.modules.file.entity.RPanUploadTask;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

@Repository("rPanUploadTaskMapper")
public interface RPanUploadTaskMapper {
  RPanUploadTask selectForUpdate(@Param("uploadId") String uploadId);

  int startFinalizing(RPanUploadTask task);

  int complete(RPanUploadTask task);

  int recordError(
      @Param("uploadId") String id, @Param("error") String error, @Param("failed") boolean failed);

  java.util.List<RPanUploadTask> selectGarbageCandidates(
      @Param("now") java.util.Date now,
      @Param("stale") java.util.Date stale,
      @Param("storageType") String storageType,
      @Param("abandonedBefore") java.util.Date abandonedBefore,
      @Param("limit") int limit);

  int expire(@Param("uploadId") String id, @Param("now") java.util.Date now);

  int artifactsCleaned(@Param("uploadId") String id, @Param("now") java.util.Date now);

  int artifactCleanupFailed(
      @Param("uploadId") String id, @Param("error") String error, @Param("now") java.util.Date now);

  int insert(RPanUploadTask task);

  int setMultipartId(
      @Param("uploadId") String uploadId,
      @Param("multipartId") String multipartId,
      @Param("locator") String locator);

  int insertPartDigest(
      @Param("uploadId") String uploadId, @Param("part") int part, @Param("md5") String md5);

  String selectPartDigest(@Param("uploadId") String uploadId, @Param("part") int part);

  java.util.List<com.rubin.rpan.modules.file.upload.model.UploadPartDigest> listPartDigests(
      @Param("uploadId") String uploadId);

  int deletePartDigests(@Param("uploadId") String uploadId);

  RPanUploadTask selectById(@Param("uploadId") String uploadId);
}
