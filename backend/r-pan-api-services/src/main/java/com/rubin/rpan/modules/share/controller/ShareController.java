package com.rubin.rpan.modules.share.controller;

import com.rubin.rpan.common.annotation.NeedLogin;
import com.rubin.rpan.common.annotation.NeedShareCode;
import com.rubin.rpan.common.response.R;
import com.rubin.rpan.modules.file.vo.RPanUserFileVO;
import com.rubin.rpan.modules.share.po.CancelSharePO;
import com.rubin.rpan.modules.share.po.CheckShareCodePO;
import com.rubin.rpan.modules.share.po.CreateSharePO;
import com.rubin.rpan.modules.share.po.ShareSavePO;
import com.rubin.rpan.modules.share.service.IShareService;
import com.rubin.rpan.modules.share.vo.RPanUserShareDetailVO;
import com.rubin.rpan.modules.share.vo.RPanUserShareSimpleDetailVO;
import com.rubin.rpan.modules.share.vo.RPanUserShareUrlVO;
import com.rubin.rpan.util.ShareIdUtil;
import com.rubin.rpan.util.UserIdUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 项目分享相关rest接口返回 */
@RestController
@Validated
@Tag(name = "分享")
public class ShareController {

  @Autowired
  @Qualifier(value = "shareService")
  private IShareService iShareService;

  /**
   * 创建分享链接
   *
   * @param createSharePO
   * @return
   */
  @Operation(summary = "创建分享链接", description = "该接口提供了创建分享链接的功能")
  @PostMapping("share")
  @NeedLogin
  public R<RPanUserShareUrlVO> create(@Validated @RequestBody CreateSharePO createSharePO) {
    return R.data(
        iShareService.create(
            createSharePO.getShareName(),
            createSharePO.getShareType(),
            createSharePO.getShareDayType(),
            createSharePO.getShareFileIds(),
            UserIdUtil.get()));
  }

  /**
   * 查询分享列表
   *
   * @return
   */
  @Operation(summary = "查询分享列表", description = "该接口提供了查询分享列表的功能")
  @GetMapping("shares")
  @NeedLogin
  public R<List<RPanUserShareUrlVO>> list() {
    return R.data(iShareService.list(UserIdUtil.get()));
  }

  /**
   * 取消分享（支持批量）
   *
   * @param cancelSharePO
   * @return
   */
  @Operation(summary = "取消分享（支持批量）", description = "该接口提供了取消分享（支持批量）的功能")
  @DeleteMapping("share")
  @NeedLogin
  public R cancel(@Validated @RequestBody CancelSharePO cancelSharePO) {
    iShareService.cancel(cancelSharePO.getShareIds(), UserIdUtil.get());
    return R.success();
  }

  /**
   * 获取分享详情
   *
   * @return
   */
  @Operation(summary = "获取分享详情", description = "该接口提供了获取分享详情的功能")
  @GetMapping("share")
  @NeedShareCode
  public R<RPanUserShareDetailVO> detail() {
    return R.data(iShareService.detail(ShareIdUtil.get()));
  }

  /**
   * 获取分享详情-简单
   *
   * @return
   */
  @Operation(summary = "获取简单分享详情", description = "该接口提供了获取简单分享详情的功能")
  @GetMapping("share/simple")
  public R<RPanUserShareSimpleDetailVO> simpleDetail(
      @NotNull(message = "分享ID不能为空") @RequestParam(value = "shareId", required = false)
          Long shareId) {
    return R.data(iShareService.simpleDetail(shareId));
  }

  /**
   * 校验分享码
   *
   * @param checkShareCodePO
   * @return
   */
  @Operation(summary = "校验分享码", description = "该接口提供了校验分享码的功能")
  @PostMapping("share/code/check")
  public R<String> checkShareCode(@Validated @RequestBody CheckShareCodePO checkShareCodePO) {
    return R.data(
        iShareService.checkShareCode(
            checkShareCodePO.getShareId(), checkShareCodePO.getShareCode()));
  }

  /**
   * 获取下一级的文件列表
   *
   * @param parentId
   * @return
   */
  @Operation(summary = "获取下一级的文件列表", description = "该接口提供了获取下一级的文件列表的功能")
  @GetMapping("share/file/list")
  @NeedShareCode
  public R<List<RPanUserFileVO>> fileList(
      @NotNull(message = "父id不能为空") @RequestParam(value = "parentId", required = false)
          Long parentId) {
    return R.data(iShareService.fileList(ShareIdUtil.get(), parentId));
  }

  /**
   * 保存至我的网盘
   *
   * @param shareSavePO
   * @return
   */
  @Operation(summary = "保存至我的网盘", description = "该接口提供了保存至我的网盘的功能")
  @PostMapping("share/save")
  @NeedShareCode
  @NeedLogin
  public R save(@Validated @RequestBody ShareSavePO shareSavePO) {
    iShareService.save(
        ShareIdUtil.get(),
        shareSavePO.getFileIds(),
        shareSavePO.getTargetParentId(),
        UserIdUtil.get());
    return R.success();
  }

  /**
   * 分享文件下载
   *
   * @param fileId
   * @param response
   * @return
   */
  @Operation(summary = "分享文件下载", description = "该接口提供了分享文件下载的功能")
  @GetMapping("share/file/download")
  @NeedShareCode
  @NeedLogin
  public void download(
      @NotNull(message = "文件id不能为空") @RequestParam(value = "fileId", required = false) Long fileId,
      HttpServletResponse response) {
    iShareService.download(ShareIdUtil.get(), fileId, response);
  }
}
