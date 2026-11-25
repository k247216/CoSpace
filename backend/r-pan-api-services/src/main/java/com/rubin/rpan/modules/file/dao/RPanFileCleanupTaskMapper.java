package com.rubin.rpan.modules.file.dao;

import com.rubin.rpan.modules.file.entity.RPanFileCleanupTask;
import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RPanFileCleanupTaskMapper {
  int insert(RPanFileCleanupTask task);

  RPanFileCleanupTask selectByRealFileId(@Param("realFileId") Long realFileId);

  RPanFileCleanupTask selectById(@Param("taskId") Long taskId);

  RPanFileCleanupTask selectByIdForUpdate(@Param("taskId") Long taskId);

  List<RPanFileCleanupTask> selectDue(@Param("now") Date now, @Param("limit") int limit);

  int reserveDelivery(
      @Param("taskId") Long taskId,
      @Param("attempts") int attempts,
      @Param("now") Date now,
      @Param("next") Date next);

  int recordDelivery(
      @Param("taskId") Long taskId,
      @Param("attempts") int attempts,
      @Param("sent") boolean sent,
      @Param("error") String error,
      @Param("now") Date now,
      @Param("executionAttempts") int executionAttempts);

  int claim(
      @Param("taskId") Long taskId,
      @Param("owner") String owner,
      @Param("until") Date until,
      @Param("now") Date now);

  int finish(@Param("taskId") Long taskId, @Param("owner") String owner, @Param("now") Date now);

  int fail(
      @Param("taskId") Long taskId,
      @Param("owner") String owner,
      @Param("state") String state,
      @Param("error") String error,
      @Param("next") Date next,
      @Param("now") Date now);

  int manual(@Param("taskId") Long taskId, @Param("error") String error, @Param("now") Date now);

  int retryManual(@Param("taskId") Long taskId, @Param("now") Date now);
}
