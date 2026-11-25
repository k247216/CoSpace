package com.rubin.rpan.modules.share.service;

import com.rubin.rpan.modules.file.vo.RPanUserFileVO;
import java.util.List;

/** 项目分享文件业务处理接口 */
public interface IShareFileService {

  void saveBatch(Long shareId, String shareFileIds, Long userId);

  void cancelShareFiles(String shareIds);

  List<RPanUserFileVO> getShareFileInfos(Long shareId);

  List<Long> getShareIdByFileIds(String fileIds);

  List<RPanUserFileVO> getAllShareFileInfos(Long shareId);

  List<Long> getFileIdsByShareId(Long shareId);
}
