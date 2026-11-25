package com.rubin.rpan.modules.file.dao;

import com.rubin.rpan.modules.file.quota.model.UploadReservation;
import com.rubin.rpan.modules.file.quota.model.UserSpaceAccount;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserSpaceMapper {
  int create(@Param("userId") Long userId, @Param("total") long total);

  UserSpaceAccount select(@Param("userId") Long userId);

  UserSpaceAccount lock(@Param("userId") Long userId);

  int reserve(@Param("userId") Long userId, @Param("bytes") long bytes);

  int charge(@Param("userId") Long userId, @Param("bytes") long bytes);

  int releaseUsed(@Param("userId") Long userId, @Param("bytes") long bytes);

  int settle(
      @Param("userId") Long userId, @Param("bytes") long bytes, @Param("commit") boolean commit);

  int insertReservation(UploadReservation reservation);

  UploadReservation findRequest(@Param("userId") Long userId, @Param("requestId") String requestId);

  UploadReservation lockReservation(@Param("uploadId") String uploadId);

  int settleReservation(@Param("uploadId") String uploadId, @Param("state") String state);
}
