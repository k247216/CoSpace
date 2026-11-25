package com.rubin.rpan.modules.recycle.service;

import com.rubin.rpan.modules.file.vo.RPanUserFileVO;
import java.util.List;

/** 回收站业务处理接口 */
public interface IRecycleBinService {

  List<RPanUserFileVO> list(Long userId);

  List<RPanUserFileVO> restore(String fileIds, Long userId);

  List<RPanUserFileVO> delete(String fileIds, Long userId);
}
