package com.rubin.rpan.modules.recycle.controller;

import com.rubin.rpan.common.annotation.NeedLogin;
import com.rubin.rpan.common.response.R;
import com.rubin.rpan.modules.file.vo.RPanUserFileVO;
import com.rubin.rpan.modules.recycle.po.RecycleBinDeletePO;
import com.rubin.rpan.modules.recycle.po.RestorePO;
import com.rubin.rpan.modules.recycle.service.IRecycleBinService;
import com.rubin.rpan.util.UserIdUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 项目回收站相关rest接口返回 */
@RestController
@Validated
@Tag(name = "回收站")
public class RecycleBinController {

  @Autowired
  @Qualifier(value = "recycleBinService")
  private IRecycleBinService iRecycleBinService;

  /**
   * 获取回收站的文件列表
   *
   * @return
   */
  @Operation(summary = "获取回收站的文件列表", description = "该接口提供了获取回收站的文件列表的功能")
  @GetMapping("recycles")
  @NeedLogin
  public R<List<RPanUserFileVO>> list() {
    return R.data(iRecycleBinService.list(UserIdUtil.get()));
  }

  /**
   * 还原文件
   *
   * @param restorePO
   * @return
   */
  @Operation(summary = "还原文件", description = "该接口提供了还原文件的功能")
  @PutMapping("recycle/restore")
  @NeedLogin
  public R<List<RPanUserFileVO>> restore(@Validated @RequestBody RestorePO restorePO) {
    return R.data(iRecycleBinService.restore(restorePO.getFileIds(), UserIdUtil.get()));
  }

  /**
   * 回收站删除文件
   *
   * @param recycleBinDeletePO
   * @return
   */
  @Operation(summary = "回收站删除文件", description = "该接口提供了回收站删除文件的功能")
  @DeleteMapping("recycle")
  @NeedLogin
  public R<List<RPanUserFileVO>> delete(
      @Validated @RequestBody RecycleBinDeletePO recycleBinDeletePO) {
    return R.data(iRecycleBinService.delete(recycleBinDeletePO.getFileIds(), UserIdUtil.get()));
  }
}
