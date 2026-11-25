package com.rubin.rpan.modules.file.controller;

import com.rubin.rpan.common.annotation.NeedLogin;
import com.rubin.rpan.common.response.R;
import com.rubin.rpan.modules.file.po.*;
import com.rubin.rpan.modules.file.service.IUserFileService;
import com.rubin.rpan.modules.file.vo.BreadcrumbVO;
import com.rubin.rpan.modules.file.vo.FolderTreeNode;
import com.rubin.rpan.modules.file.vo.RPanUserFileVO;
import com.rubin.rpan.util.UserIdUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 项目文件相关rest接口返回 */
@RestController
@Validated
@Tag(name = "文件接口")
public class FileRestController {

  @Autowired
  @Qualifier(value = "userFileService")
  private IUserFileService iUserFileService;

  /** 获取文件列表 */
  @Operation(summary = "获取文件列表", description = "该接口提供了获取文件列表的功能")
  @GetMapping("files")
  @NeedLogin
  public R<List<RPanUserFileVO>> list(
      @NotNull(message = "父id不能为空") @RequestParam(value = "parentId", required = false)
          Long parentId,
      @RequestParam(name = "fileTypes", required = false, defaultValue = "-1") String fileTypes) {
    return R.data(iUserFileService.list(parentId, fileTypes, UserIdUtil.get()));
  }

  /**
   * 新建文件夹
   *
   * @param createFolderPO
   * @return
   */
  @Operation(summary = "新建文件夹", description = "该接口提供了新建文件夹的功能")
  @PostMapping("file/folder")
  @NeedLogin
  public R<List<RPanUserFileVO>> createFolder(
      @Validated @RequestBody CreateFolderPO createFolderPO) {
    return R.data(
        iUserFileService.createFolder(
            createFolderPO.getParentId(), createFolderPO.getFolderName(), UserIdUtil.get()));
  }

  /**
   * 文件重命名
   *
   * @param updateFileNamePO
   * @return
   */
  @Operation(summary = "文件重命名", description = "该接口提供了文件重命名的功能")
  @PutMapping("file")
  @NeedLogin
  public R<List<RPanUserFileVO>> updateFilename(
      @Validated @RequestBody UpdateFileNamePO updateFileNamePO) {
    return R.data(
        iUserFileService.updateFilename(
            updateFileNamePO.getFileId(), updateFileNamePO.getNewFilename(), UserIdUtil.get()));
  }

  /**
   * 删除文件(批量)
   *
   * @param deletePO
   * @return
   */
  @Operation(summary = "删除文件(批量)", description = "该接口提供了删除文件(批量)的功能")
  @DeleteMapping("file")
  @NeedLogin
  public R<List<RPanUserFileVO>> delete(@Validated @RequestBody DeletePO deletePO) {
    return R.data(
        iUserFileService.delete(deletePO.getParentId(), deletePO.getFileIds(), UserIdUtil.get()));
  }

  /**
   * 秒传文件
   *
   * @param fileSecUploadPO
   * @return
   */
  @Operation(summary = "秒传文件", description = "该接口提供了秒传文件的功能")
  @PostMapping("file/sec-upload")
  @NeedLogin
  public R secUpload(@Validated @RequestBody FileSecUploadPO fileSecUploadPO) {
    if (iUserFileService.secUpload(
        fileSecUploadPO.getParentId(),
        fileSecUploadPO.getName(),
        fileSecUploadPO.getMd5(),
        fileSecUploadPO.getSize(),
        UserIdUtil.get())) {
      return R.success();
    }
    return R.fail("秒传未命中，请上传文件");
  }

  /**
   * 下载文件(只支持单个下载)
   *
   * @param fileId
   * @param response
   */
  @Operation(summary = "下载文件(只支持单个下载)", description = "该接口提供了下载文件(只支持单个下载)的功能")
  @GetMapping("file/download")
  @NeedLogin
  public void download(
      @NotNull(message = "请选择要下载的文件") @RequestParam(value = "fileId", required = false) Long fileId,
      HttpServletResponse response) {
    iUserFileService.download(fileId, response, UserIdUtil.get());
  }

  /**
   * 获取文件夹树
   *
   * @return
   */
  @Operation(summary = "获取文件夹树", description = "该接口提供了获取文件夹树的功能")
  @GetMapping("file/folder/tree")
  @NeedLogin
  public R<List<FolderTreeNode>> getFolderTree() {
    return R.data(iUserFileService.getFolderTree(UserIdUtil.get()));
  }

  /**
   * 转移文件(批量)
   *
   * @param transferPO
   * @return
   */
  @Operation(summary = "转移文件(批量)", description = "该接口提供了转移文件(批量)的功能")
  @PostMapping("file/transfer")
  @NeedLogin
  public R<List<RPanUserFileVO>> transfer(@Validated @RequestBody TransferPO transferPO) {
    return R.data(
        iUserFileService.transfer(
            transferPO.getFileIds(),
            transferPO.getParentId(),
            transferPO.getTargetParentId(),
            UserIdUtil.get()));
  }

  /**
   * 复制文件(批量)
   *
   * @param copyPO
   * @return
   */
  @Operation(summary = "复制文件(批量)", description = "该接口提供了复制文件(批量)的功能")
  @PostMapping("file/copy")
  @NeedLogin
  public R<List<RPanUserFileVO>> copy(@Validated @RequestBody CopyPO copyPO) {
    return R.data(
        iUserFileService.copy(
            copyPO.getFileIds(),
            copyPO.getParentId(),
            copyPO.getTargetParentId(),
            UserIdUtil.get()));
  }

  /**
   * 通过文件名搜索文件列表
   *
   * @param keyword
   * @param fileTypes
   * @return
   */
  @Operation(summary = "通过文件名搜索文件列表", description = "该接口提供了通过文件名搜索文件列表的功能")
  @GetMapping("file/search")
  @NeedLogin
  public R<List<RPanUserFileVO>> search(
      @NotBlank(message = "关键字不能为空") @RequestParam(value = "keyword", required = false)
          String keyword,
      @RequestParam(name = "fileTypes", required = false, defaultValue = "-1") String fileTypes) {
    return R.data(iUserFileService.search(keyword, fileTypes, UserIdUtil.get()));
  }

  /**
   * 查询文件详情
   *
   * @param fileId
   * @return
   */
  @Operation(summary = "查询文件详情", description = "该接口提供了查询文件详情的功能")
  @GetMapping("file")
  @NeedLogin
  public R<RPanUserFileVO> detail(
      @NotNull(message = "文件id不能为空") @RequestParam(value = "fileId", required = false)
          Long fileId) {
    return R.data(iUserFileService.detail(fileId, UserIdUtil.get()));
  }

  /**
   * 获取面包屑列表
   *
   * @return
   */
  @Operation(summary = "获取面包屑列表", description = "该接口提供了获取面包屑列表的功能")
  @GetMapping("file/breadcrumbs")
  @NeedLogin
  public R<List<BreadcrumbVO>> getBreadcrumbs(
      @NotNull(message = "文件id不能为空") @RequestParam(value = "fileId", required = false)
          Long fileId) {
    return R.data(iUserFileService.getBreadcrumbs(fileId, UserIdUtil.get()));
  }

  /**
   * 预览单个文件
   *
   * @param fileId
   * @return
   */
  @Operation(summary = "预览单个文件", description = "该接口提供了预览单个文件的功能")
  @GetMapping("preview")
  @NeedLogin
  public void preview(
      @NotNull(message = "文件id不能为空") @RequestParam(value = "fileId", required = false) Long fileId,
      HttpServletResponse response) {
    iUserFileService.preview(fileId, response, UserIdUtil.get());
  }
}
