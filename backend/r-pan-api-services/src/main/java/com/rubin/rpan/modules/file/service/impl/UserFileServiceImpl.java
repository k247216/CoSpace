package com.rubin.rpan.modules.file.service.impl;

import com.google.common.collect.Lists;
import com.rubin.rpan.common.constant.CommonConstant;
import com.rubin.rpan.common.exception.RPanException;
import com.rubin.rpan.common.response.ResponseCode;
import com.rubin.rpan.modules.file.constant.FileConstant;
import com.rubin.rpan.modules.file.constant.FileLifecycleState;
import com.rubin.rpan.modules.file.dao.RPanUserFileMapper;
import com.rubin.rpan.modules.file.entity.RPanFile;
import com.rubin.rpan.modules.file.entity.RPanUserFile;
import com.rubin.rpan.modules.file.quota.service.UserSpaceService;
import com.rubin.rpan.modules.file.service.IFileService;
import com.rubin.rpan.modules.file.service.IUserFileService;
import com.rubin.rpan.modules.file.vo.BreadcrumbVO;
import com.rubin.rpan.modules.file.vo.FolderTreeNode;
import com.rubin.rpan.modules.file.vo.RPanUserFileVO;
import com.rubin.rpan.modules.share.service.IShareService;
import com.rubin.rpan.modules.user.service.IUserSearchHistoryService;
import com.rubin.rpan.storage.StorageManager;
import com.rubin.rpan.util.FileUtil;
import com.rubin.rpan.util.HttpUtil;
import com.rubin.rpan.util.IdGenerator;
import com.rubin.rpan.util.StringListUtil;
import com.rubin.rpan.util.file.type.context.FileTypeContext;
import jakarta.servlet.http.HttpServletResponse;
import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户文件业务处理实现 */
@Service(value = "userFileService")
@Transactional(rollbackFor = Exception.class)
public class UserFileServiceImpl implements IUserFileService {
  @Autowired private UserSpaceService space;

  private static final Logger log = LoggerFactory.getLogger(UserFileServiceImpl.class);

  @Autowired
  @Qualifier(value = "rPanUserFileMapper")
  private RPanUserFileMapper rPanUserFileMapper;

  @Autowired
  @Qualifier(value = "fileService")
  private IFileService iFileService;

  @Autowired private FileLifecycleService fileLifecycleService;

  @Autowired
  @Qualifier(value = "userSearchHistoryService")
  private IUserSearchHistoryService iUserSearchHistoryService;

  @Autowired
  @Qualifier(value = "shareService")
  @Lazy // Refresh share status only when a file is moved to the recycle bin.
  private IShareService iShareService;

  /**
   * 获取文件列表
   *
   * @param parentId
   * @param fileTypes
   * @param userId
   * @return
   */
  @Override
  public List<RPanUserFileVO> list(Long parentId, String fileTypes, Long userId) {
    return list(parentId, fileTypes, userId, FileConstant.DelFlagEnum.NO.getCode());
  }

  /**
   * 获取文件列表
   *
   * @param parentId
   * @param fileTypes
   * @param userId
   * @param delFlag
   * @return
   */
  @Override
  public List<RPanUserFileVO> list(Long parentId, String fileTypes, Long userId, Integer delFlag) {
    List<Integer> fileTypeArray = null;
    if (Objects.equals(CommonConstant.ZERO_LONG, parentId)) {
      return Lists.newArrayList();
    }
    if (!Objects.equals(fileTypes, FileConstant.ALL_FILE_TYPE)) {
      fileTypeArray = StringListUtil.string2IntegerList(fileTypes);
    }
    return rPanUserFileMapper.selectRPanUserFileVOListByUserIdAndFileTypeAndParentIdAndDelFlag(
        userId, fileTypeArray, parentId, delFlag);
  }

  /**
   * 获取文件列表
   *
   * @param fileIds
   * @return
   */
  @Override
  public List<RPanUserFileVO> list(String fileIds) {
    return rPanUserFileMapper.selectRPanUserFileVOListByFileIdList(
        StringListUtil.string2LongList(fileIds));
  }

  @Override
  public List<RPanUserFile> requireOwnedLiveFiles(String fileIds, Long userId) {
    if (userId == null || userId <= 0 || StringUtils.isBlank(fileIds))
      throw new RPanException("请选择自己的有效文件");
    final Set<Long> ids;
    try {
      ids = new TreeSet<>(StringListUtil.string2LongList(fileIds));
    } catch (NumberFormatException malformed) {
      throw new RPanException("文件ID格式不合法");
    }
    if (ids.isEmpty() || ids.stream().anyMatch(id -> id == null || id <= 0))
      throw new RPanException("文件ID不合法");
    space.lock(userId);
    List<RPanUserFile> result = new ArrayList<>();
    for (Long id : ids) {
      // 先按用户过滤，避免为明显越权的请求锁定别人的记录。
      if (rPanUserFileMapper.selectByFileIdAndUserId(id, userId) == null)
        throw new RPanException("只能操作自己的有效文件");
      RPanUserFile current = rPanUserFileMapper.selectByIdForUpdate(id);
      if (current == null
          || !Objects.equals(current.getUserId(), userId)
          || !isCurrentSourceAvailable(id, userId)) throw new RPanException("文件或上级目录不可用");
      result.add(current);
    }
    return result;
  }

  /**
   * 创建文件夹
   *
   * @param parentId
   * @param folderName
   * @param userId
   * @return
   */
  @Override
  public List<RPanUserFileVO> createFolder(Long parentId, String folderName, Long userId) {
    saveUserFile(parentId, folderName, FileConstant.FolderFlagEnum.YES, null, null, userId, null);
    return list(
        parentId, FileConstant.ALL_FILE_TYPE, userId, FileConstant.DelFlagEnum.NO.getCode());
  }

  /**
   * 文件重命名
   *
   * @param fileId
   * @param newFilename
   * @param userId
   * @return
   */
  @Override
  public List<RPanUserFileVO> updateFilename(Long fileId, String newFilename, Long userId) {
    RPanUserFile originalUserFileInfo = getRPanUserFileByFileIdAndParentId(fileId, userId);
    if (Objects.equals(originalUserFileInfo.getFilename(), newFilename)) {
      throw new RPanException("请使用一个新名称");
    }
    if (rPanUserFileMapper.selectCountByUserIdAndFilenameAndParentId(
            userId, newFilename, originalUserFileInfo.getParentId())
        > CommonConstant.ZERO_INT) {
      throw new RPanException("名称已被占用");
    }
    originalUserFileInfo.setFilename(newFilename);
    originalUserFileInfo.setUpdateUser(userId);
    originalUserFileInfo.setUpdateTime(new Date());
    if (rPanUserFileMapper.updateByPrimaryKeySelective(originalUserFileInfo)
        != CommonConstant.ONE_INT) {
      throw new RPanException("文件重命名失败");
    }
    return list(originalUserFileInfo.getParentId(), FileConstant.ALL_FILE_TYPE, userId);
  }

  /**
   * 删除文件(批量)
   *
   * @param parentId
   * @param fileIds
   * @param userId
   * @return
   */
  @Override
  public List<RPanUserFileVO> delete(Long parentId, String fileIds, Long userId) {
    List<Long> idList = StringListUtil.string2LongList(fileIds);
    if (rPanUserFileMapper.deleteBatch(idList, userId) != idList.size()) {
      throw new RPanException("删除失败");
    }
    iShareService.refreshShareStatus(getAllAvailableFileIdByFileIds(fileIds));
    return list(parentId, FileConstant.ALL_FILE_TYPE, userId);
  }

  /**
   * 文件下载
   *
   * @param fileId
   * @param response
   * @param userId
   */
  @Override
  public void download(Long fileId, HttpServletResponse response, Long userId) {
    try {
      getRPanUserFileByFileIdAndParentId(fileId, userId);
    } catch (RPanException e) {
      throw new RPanException("您没有下载权限");
    }
    download(fileId, response);
  }

  /**
   * 文件下载
   *
   * @param fileId
   * @param response
   */
  @Override
  public void download(Long fileId, HttpServletResponse response) {
    if (checkIsFolder(fileId)) {
      throw new RPanException("不能选择文件夹下载");
    }
    RPanUserFile rPanUserFile = rPanUserFileMapper.selectByPrimaryKey(fileId);
    RPanFile rPanFile = iFileService.getFileDetail(rPanUserFile.getRealFileId());
    doDownload(rPanFile, response, rPanUserFile.getFilename());
  }

  /**
   * 获取文件夹树
   *
   * @param userId
   * @return
   */
  @Override
  public List<FolderTreeNode> getFolderTree(Long userId) {
    List<RPanUserFile> folderList = rPanUserFileMapper.selectFolderListByUserId(userId);
    return assembleFolderTree(folderList);
  }

  /**
   * 批量转移文件
   *
   * @param fileIds
   * @param parentId
   * @param targetParentId
   * @param userId
   * @return
   */
  @Override
  public List<RPanUserFileVO> transfer(
      String fileIds, Long parentId, Long targetParentId, Long userId) {
    List<RPanUserFile> sources = requireOwnedLiveFiles(fileIds, userId);
    if (sources.stream()
        .anyMatch(file -> Objects.equals(file.getParentId(), CommonConstant.ZERO_LONG)))
      throw new RPanException("不能移动根目录");
    validateSecUploadParent(targetParentId, userId);
    if (!checkTargetParentIdAvailable(targetParentId, fileIds)) {
      throw new RPanException("要转移的文件中包含选中的目标文件夹,请重新选择");
    }
    sources.forEach(file -> transferOne(file, targetParentId, userId));
    return list(parentId, FileConstant.ALL_FILE_TYPE, userId);
  }

  /**
   * 批量复制文件
   *
   * @param fileIds
   * @param parentId
   * @param targetParentId
   * @param userId
   * @return
   */
  @Override
  public List<RPanUserFileVO> copy(
      String fileIds, Long parentId, Long targetParentId, Long userId) {
    space.lock(userId);
    if (!checkIsFolder(targetParentId, userId)) {
      throw new RPanException("请选择要复制到的文件夹");
    }
    if (!checkTargetParentIdAvailable(targetParentId, fileIds)) {
      throw new RPanException("要复制的文件中包含选中的目标文件夹,请重新选择");
    }
    doCopyUserFiles(fileIds, targetParentId, userId, false);
    return list(parentId, FileConstant.ALL_FILE_TYPE, userId);
  }

  /**
   * 通过文件名搜索文件
   *
   * @param keyword
   * @param userId
   * @return
   */
  @Override
  public List<RPanUserFileVO> search(String keyword, String fileTypes, Long userId) {
    saveUserSearchHistory(keyword, userId);
    List<Integer> fileTypeArray = null;
    if (!Objects.equals(fileTypes, FileConstant.ALL_FILE_TYPE)) {
      fileTypeArray = StringListUtil.string2IntegerList(fileTypes);
    }
    return rPanUserFileMapper.selectRPanUserFileVOListByUserIdAndFilenameAndFileTypes(
        userId, keyword, fileTypeArray);
  }

  /**
   * 查询文件详情
   *
   * @param fileId
   * @param userId
   * @return
   */
  @Override
  public RPanUserFileVO detail(Long fileId, Long userId) {
    return rPanUserFileMapper.selectRPanUserFileVOByFileIdAndUserId(fileId, userId);
  }

  /**
   * 获取面包屑列表
   *
   * @param fileId
   * @param userId
   * @return
   */
  @Override
  public List<BreadcrumbVO> getBreadcrumbs(Long fileId, Long userId) {
    List<RPanUserFile> rPanUserFileList = rPanUserFileMapper.selectFolderListByUserId(userId);
    if (CollectionUtils.isEmpty(rPanUserFileList)) {
      return Lists.newArrayList();
    }
    Map<Long, BreadcrumbVO> breadcrumbVOTransferMap =
        rPanUserFileList.stream()
            .collect(Collectors.toMap(RPanUserFile::getFileId, BreadcrumbVO::assembleBreadcrumbVO));
    List<BreadcrumbVO> breadcrumbVOList = Lists.newArrayList();
    BreadcrumbVO thisLevel = breadcrumbVOTransferMap.get(fileId);
    do {
      breadcrumbVOList.add(thisLevel);
      thisLevel = breadcrumbVOTransferMap.get(thisLevel.getParentId());
    } while (!Objects.isNull(thisLevel));
    Collections.reverse(breadcrumbVOList);
    return breadcrumbVOList;
  }

  /** 调用方已完成空间授权。允许的媒体类型直接预览，其余类型按文本读取。 */
  @Override
  public void previewAuthorized(Long fileId, HttpServletResponse response) {
    RPanUserFile reference = rPanUserFileMapper.selectByPrimaryKey(fileId);
    RPanFile file = iFileService.getFileDetail(reference.getRealFileId());
    String type = file.getFilePreviewContentType();
    boolean safe =
        type != null
            && java.util.Set.of(
                    "image/png",
                    "image/jpeg",
                    "image/gif",
                    "image/webp",
                    "application/pdf",
                    "text/plain",
                    "audio/mpeg",
                    "video/mp4")
                .contains(type);
    addCommonResponseHeader(response, safe ? type : "text/plain");
    response.setHeader("X-Content-Type-Options", "nosniff");
    read2OutputStream(file, response);
  }

  @Override
  public void preview(Long fileId, HttpServletResponse response, Long userId) {
    if (checkIsFolder(fileId, userId)) {
      throw new RPanException("不能预览文件夹");
    }
    RPanUserFile rPanUserFile = rPanUserFileMapper.selectByPrimaryKey(fileId);
    RPanFile fileDetail = iFileService.getFileDetail(rPanUserFile.getRealFileId());
    preview(fileDetail, response);
  }

  /**
   * 批量还原用户文件的删除状态
   *
   * @param fileIds
   * @param userId
   * @return
   */
  @Override
  public void restoreUserFiles(String fileIds, Long userId) {
    if (StringUtils.isBlank(fileIds) || Objects.isNull(userId)) {
      throw new RPanException("批量还原用户文件的删除状态失败");
    }
    List<Long> fileIdList = StringListUtil.string2LongList(fileIds);
    List<RPanUserFile> rPanUserFiles = rPanUserFileMapper.selectListByFileIdList(fileIdList);
    Set<String> cleanSet =
        rPanUserFiles.stream()
            .map(rPanUserFile -> rPanUserFile.getParentId() + rPanUserFile.getFilename())
            .collect(Collectors.toSet());
    if (cleanSet.size() != rPanUserFiles.size()) {
      throw new RPanException("文件还原失败，该还原文件中存在同名文件，请逐个还原并重命名");
    }
    for (RPanUserFile rPanUserFile : rPanUserFiles) {
      if (rPanUserFileMapper.selectCountByUserIdAndFilenameAndParentId(
              rPanUserFile.getUserId(), rPanUserFile.getFilename(), rPanUserFile.getParentId())
          != CommonConstant.ZERO_INT) {
        throw new RPanException(
            "文件：" + rPanUserFile.getFilename() + "还原失败，该文件夹中已有相同名称的文件或文件夹，请重命名后重试");
      }
    }
    if (rPanUserFileMapper.updateUserFileDelFlagByFileIdListAndUserId(fileIdList, userId)
        != fileIdList.size()) {
      throw new RPanException("批量还原用户文件的删除状态失败");
    }
  }

  /**
   * 物理删除用户文件
   *
   * @param fileIds
   * @param userId
   * @return
   */
  @Override
  public void physicalDeleteUserFiles(String fileIds, Long userId) {
    if (StringUtils.isBlank(fileIds) || Objects.isNull(userId)) {
      throw new RPanException("物理删除用户文件失败");
    }
    space.lock(userId);
    // 入口校验，请求的记录没有全部查到，说明至少有一个 ID 不存在，拒绝整次操作，是一种严格操作。不是当前用户的记录，根目录，不在回收站都不能
    // Set去重的是用户提交的选中 ID。
    Set<Long> requested = new HashSet<>(StringListUtil.string2LongList(fileIds));
    List<RPanUserFile> roots =
        rPanUserFileMapper.selectListByFileIdList(new ArrayList<>(requested));
    if (roots.size() != requested.size()
        || roots.stream()
            .anyMatch(
                f ->
                    !Objects.equals(f.getUserId(), userId)
                        || !Objects.equals(f.getDelFlag(), FileConstant.DelFlagEnum.YES.getCode())
                        || Objects.equals(f.getParentId(), CommonConstant.ZERO_LONG))) {
      throw new RPanException("只能彻底删除自己的回收站记录");
    }

    // 先发现候选，再按物理ID统一取锁；锁后重新核对用户记录，不能信任旧快照。
    List<RPanUserFile> files = assembleAllToBeDeletedUserFileList(fileIds);
    // 按 fileId 去重，避免同一条记录被重复删除、影响行数检查出错。
    Map<Long, RPanUserFile> distinct = new LinkedHashMap<>();
    files.forEach(f -> distinct.put(f.getFileId(), f));
    files = new ArrayList<>(distinct.values());
    // 拿到物理ID并且取锁
    Set<Long> realIds = physicalIds(files);
    fileLifecycleService.lockFiles(realIds);
    for (RPanUserFile before :
        files.stream()
            .sorted(Comparator.comparing(RPanUserFile::getFileId))
            .collect(Collectors.toList())) {
      RPanUserFile current = rPanUserFileMapper.selectByIdForUpdate(before.getFileId());
      if (!sameReference(before, current) || !Objects.equals(current.getUserId(), userId)) {
        throw new RPanException("文件记录已变化，请刷新后重试");
      }
    }
    // 这里只去删用户引用记录
    List<Long> ids = files.stream().map(RPanUserFile::getFileId).collect(Collectors.toList());
    if (rPanUserFileMapper.physicalDeleteBatch(ids, userId) != ids.size()) {
      throw new RPanException("物理删除用户文件记录失败");
    }
    long released = 0;
    for (RPanUserFile file : files) released = Math.addExact(released, file.getSpaceBytes());
    space.releaseUsed(userId, released);
    // 无引用才标记DELETING并创建任务；与上面的引用删除同事务，不删除磁盘。
    fileLifecycleService.scheduleIfUnreferenced(realIds);
  }

  /**
   * 批量保存文件
   *
   * @param fileIds
   * @param targetParentId
   * @param userId
   * @return
   */
  @Override
  public void saveBatch(String fileIds, Long targetParentId, Long userId) {
    space.lock(userId);
    if (!checkIsFolder(targetParentId, userId)) {
      throw new RPanException("请选择要复制到的文件夹");
    }
    if (!checkTargetParentIdAvailable(targetParentId, fileIds)) {
      throw new RPanException("要复制的文件中包含选中的目标文件夹,请重新选择");
    }
    doCopyUserFiles(fileIds, targetParentId, userId, true);
  }

  /**
   * 获取对应文件列表的所有文件以及子文件信息
   *
   * @param fileIds
   * @return
   */
  @Override
  public List<RPanUserFileVO> allList(String fileIds) {
    if (StringUtils.isBlank(fileIds)) {
      throw new RPanException(ResponseCode.ERROR_PARAM);
    }
    List<RPanUserFileVO> rPanUserFileVOList =
        rPanUserFileMapper.selectRPanUserFileVOListByFileIdList(
            StringListUtil.string2LongList(fileIds));
    if (CollectionUtils.isEmpty(rPanUserFileVOList)) {
      return Lists.newArrayList();
    }
    final List<RPanUserFileVO> allRPanUserFileVOList = Lists.newArrayList(rPanUserFileVOList);
    rPanUserFileVOList.stream()
        .forEach(
            rPanUserFileVO -> findAllAvailableChildUserFile(allRPanUserFileVOList, rPanUserFileVO));
    return allRPanUserFileVOList;
  }

  /**
   * 获取用户的顶级文件信息
   *
   * @param userId
   * @return
   */
  @Override
  public RPanUserFile getUserTopFileInfo(Long userId) {
    return rPanUserFileMapper.selectTopFolderByUserId(userId);
  }

  /**
   * 通过文件id集合获取所有文件id和子文件id
   *
   * @param fileIds
   * @return
   */
  @Override
  public String getAllAvailableFileIdByFileIds(String fileIds) {
    List<Long> fileIdList = StringListUtil.string2LongList(fileIds);
    List<Long> allAvailableFileId = Lists.newArrayList(fileIdList);
    fileIdList.stream()
        .forEach(fileId -> findAllAvailableFileIdByFileId(allAvailableFileId, fileId));
    return StringListUtil.longListToString(allAvailableFileId);
  }

  /**
   * 校验所有的父文件夹以及当前文件有效
   *
   * @param fileIds
   * @return
   */
  @Override
  public boolean checkAllUpFileAvailable(List<Long> fileIds) {
    for (Long fileId : fileIds) {
      if (!checkUpFileAvailable(fileId)) {
        return false;
      }
    }
    return true;
  }

  /**
   * 秒传文件
   *
   * @param parentId
   * @param filename
   * @param md5
   * @param userId
   * @return
   */
  @Override
  public boolean secUpload(Long parentId, String filename, String md5, Long size, Long userId) {
    space.lock(userId);
    // 检查摘要、大小等参数
    if (userId == null
        || size == null
        || size < 0
        || md5 == null
        || !md5.matches("[0-9a-fA-F]{32}")
        || StringUtils.isBlank(filename)) {
      throw new RPanException("秒传参数不合法");
    }
    // 检查目标目录
    validateSecUploadParent(parentId, userId);
    // 查找当前用户有权复用的物理文件
    RPanFile rPanFile = iFileService.findReusableFile(md5.toLowerCase(Locale.ROOT), size, userId);
    if (rPanFile == null) {
      return false;
    }
    RPanFile current =
        fileLifecycleService
            .lockFiles(Collections.singleton(rPanFile.getFileId()))
            .get(rPanFile.getFileId());
    // 候选查询是普通读；拿到物理行锁后重新确认生命周期、内容参数和当前授权。
    if (current.getLifecycleState() != FileLifecycleState.ACTIVE
        || !Objects.equals(current.getMd5(), md5.toLowerCase(Locale.ROOT))
        || !Objects.equals(current.getFileSize(), String.valueOf(size))) {
      return false;
    }
    List<Long> references =
        rPanUserFileMapper.selectLiveReferenceIdsForUpdate(current.getFileId(), userId);
    boolean authorized = references.stream().anyMatch(id -> isCurrentSourceAvailable(id, userId));
    if (!authorized) return false;
    // 生成新的用户文件 ID，保存当前用户、目标目录和显示名称，把已有物理文件 ID 填进 real_file_id
    saveUserFile(
        parentId,
        filename,
        FileConstant.FolderFlagEnum.NO,
        FileTypeContext.getFileTypeCode(filename),
        rPanFile.getFileId(),
        userId,
        rPanFile.getFileSizeDesc());
    return true;
  }

  private void validateSecUploadParent(Long parentId, Long userId) {
    Set<Long> visited = new HashSet<>();
    Long current = parentId;
    while (current != null && current > 0 && visited.add(current)) {
      RPanUserFile folder = rPanUserFileMapper.selectByFileIdAndUserId(current, userId);
      if (folder == null
          || !Objects.equals(folder.getUserId(), userId)
          || !Objects.equals(folder.getFolderFlag(), FileConstant.FolderFlagEnum.YES.getCode())
          || !Objects.equals(folder.getDelFlag(), FileConstant.DelFlagEnum.NO.getCode())) {
        throw new RPanException("目标目录不可用");
      }
      if (Objects.equals(folder.getParentId(), CommonConstant.ZERO_LONG)) {
        return;
      }
      current = folder.getParentId();
    }
    throw new RPanException("目标目录不可用");
  }

  /**
   * 拼装用户文件实体信息
   *
   * @param parentId
   * @param userId
   * @param filename
   * @param folderFlag
   * @param fileType
   * @param realFileId
   * @param fileSizeDesc
   * @return
   */
  private RPanUserFile assembleRPanUserFile(
      Long parentId,
      Long userId,
      String filename,
      FileConstant.FolderFlagEnum folderFlag,
      Integer fileType,
      Long realFileId,
      String fileSizeDesc) {
    RPanUserFile rPanUserFile = new RPanUserFile();
    rPanUserFile.setUserId(userId);
    rPanUserFile.setParentId(parentId);
    rPanUserFile.setFileId(IdGenerator.nextId());
    rPanUserFile.setFilename(filename);
    rPanUserFile.setFileType(fileType);
    rPanUserFile.setFolderFlag(folderFlag.getCode());
    rPanUserFile.setFileSizeDesc(fileSizeDesc);
    rPanUserFile.setRealFileId(realFileId);
    rPanUserFile.setCreateUser(userId);
    rPanUserFile.setCreateTime(new Date());
    rPanUserFile.setUpdateUser(userId);
    rPanUserFile.setUpdateTime(new Date());
    // 查询当前用户、当前目录下未删除的文件记录，遇到同名时尝试添加括号编号。这里修改的是用户看到的名字，不会重命名磁盘文件
    handleDuplicateFileName(rPanUserFile);
    return rPanUserFile;
  }

  /**
   * 保存用户文件信息
   *
   * @param parentId
   * @param filename
   * @param folderFlag
   * @param fileType
   * @param realFileId
   * @param userId
   * @param fileSizeDesc
   * @return
   */
  private void saveUserFile(
      Long parentId,
      String filename,
      FileConstant.FolderFlagEnum folderFlag,
      Integer fileType,
      Long realFileId,
      Long userId,
      String fileSizeDesc) {
    long bytes = 0;
    if (folderFlag == FileConstant.FolderFlagEnum.NO) {
      fileLifecycleService.lockActiveFiles(Collections.singleton(realFileId));
      bytes = UserSpaceService.fileBytes(iFileService.getFileDetail(realFileId));
      space.charge(userId, bytes);
    }
    RPanUserFile record =
        assembleRPanUserFile(
            parentId, userId, filename, folderFlag, fileType, realFileId, fileSizeDesc);
    record.setSpaceBytes(bytes);
    if (rPanUserFileMapper.insertSelective(record) != CommonConstant.ONE_INT) {
      throw new RPanException("保存文件信息失败");
    }
  }

  /**
   * 处理重复文件名
   *
   * @param rPanUserFile
   */
  private void handleDuplicateFileName(RPanUserFile rPanUserFile) {
    String newFileName = rPanUserFile.getFilename(), newFileNameWithoutSuffix, newFileNameSuffix;
    int newFileNamePointPosition = newFileName.lastIndexOf(CommonConstant.POINT_STR);
    if (newFileNamePointPosition == CommonConstant.MINUS_ONE_INT) {
      newFileNameWithoutSuffix = newFileName;
      newFileNameSuffix = CommonConstant.EMPTY_STR;
    } else {
      newFileNameWithoutSuffix =
          newFileName.substring(CommonConstant.ZERO_INT, newFileNamePointPosition);
      newFileNameSuffix = FileUtil.getFileSuffix(newFileName);
    }
    List<RPanUserFileVO> rPanUserFileVOList =
        rPanUserFileMapper.selectRPanUserFileVOListByUserIdAndFileTypeAndParentIdAndDelFlag(
            rPanUserFile.getUserId(),
            null,
            rPanUserFile.getParentId(),
            FileConstant.DelFlagEnum.NO.getCode());
    boolean noDuplicateFileNameFlag =
        rPanUserFileVOList.stream()
            .noneMatch(
                rPanUserFileVO -> rPanUserFile.getFilename().equals(rPanUserFileVO.getFilename()));
    if (noDuplicateFileNameFlag) {
      return;
    }
    List<String> duplicateFileNameList =
        rPanUserFileVOList.stream()
            .map(RPanUserFileVO::getFilename)
            .filter(fileName -> fileName.startsWith(newFileNameWithoutSuffix))
            .filter(
                fileName -> {
                  int pointPosition = fileName.lastIndexOf(CommonConstant.POINT_STR);
                  String fileNameSuffix = CommonConstant.EMPTY_STR;
                  if (pointPosition != CommonConstant.MINUS_ONE_INT) {
                    fileNameSuffix = FileUtil.getFileSuffix(fileName);
                  }
                  return Objects.equals(newFileNameSuffix, fileNameSuffix);
                })
            .collect(Collectors.toList());
    if (CollectionUtils.isEmpty(duplicateFileNameList)) {
      return;
    }
    newFileName =
        new StringBuilder(newFileNameWithoutSuffix)
            .append(FileConstant.CN_LEFT_PARENTHESES_STR)
            .append((duplicateFileNameList.size()))
            .append(FileConstant.CN_RIGHT_PARENTHESES_STR)
            .append(newFileNameSuffix)
            .toString();
    rPanUserFile.setFilename(newFileName);
  }

  /**
   * 执行下载文件
   *
   * @param file 物理文件记录
   * @param response
   * @param filename
   */
  private void doDownload(RPanFile file, HttpServletResponse response, String filename) {
    addCommonResponseHeader(response, FileConstant.APPLICATION_OCTET_STREAM_STR);
    try {
      response.setHeader(
          FileConstant.CONTENT_DISPOSITION_STR,
          FileConstant.CONTENT_DISPOSITION_VALUE_PREFIX_STR
              + new String(
                  filename.getBytes(FileConstant.GB2312_STR), FileConstant.IOS_8859_1_STR));
    } catch (UnsupportedEncodingException e) {
      log.error("下载文件失败", e);
    }
    read2OutputStream(file, response);
  }

  /**
   * 补全要复制的文件列表
   *
   * @param toBeCopiedFileInfoList
   * @param targetParentId
   * @param userId
   */
  private void complementToBeCopiedFileInfoList(
      final List<RPanUserFile> toBeCopiedFileInfoList,
      final Long targetParentId,
      final Long userId) {
    final List<RPanUserFile> allChildUserFileList = Lists.newArrayList();
    toBeCopiedFileInfoList.stream()
        .forEach(
            rPanUserFile -> {
              Long fileId = rPanUserFile.getFileId(), newFileId = IdGenerator.nextId();
              rPanUserFile.setParentId(targetParentId);
              rPanUserFile.setUserId(userId);
              rPanUserFile.setFileId(newFileId);
              rPanUserFile.setCreateUser(userId);
              rPanUserFile.setCreateTime(new Date());
              rPanUserFile.setUpdateUser(userId);
              rPanUserFile.setUpdateTime(new Date());
              handleDuplicateFileName(rPanUserFile);
              if (checkIsFolder(rPanUserFile)) {
                assembleAllChildUserFile(allChildUserFileList, fileId, newFileId, userId);
              }
            });
    toBeCopiedFileInfoList.addAll(allChildUserFileList);
  }

  /**
   * 文件预览
   *
   * @param file 物理文件记录
   * @param response
   */
  private void preview(RPanFile file, HttpServletResponse response) {
    addCommonResponseHeader(response, file.getFilePreviewContentType());
    read2OutputStream(file, response);
  }

  /**
   * 添加公用的响应头
   *
   * @param response
   * @param contentTypeValue
   */
  private void addCommonResponseHeader(HttpServletResponse response, String contentTypeValue) {
    response.reset();
    HttpUtil.addCorsResponseHeader(response);
    response.setHeader(FileConstant.CONTENT_TYPE_STR, contentTypeValue);
    response.setContentType(contentTypeValue);
  }

  @Autowired
  @Qualifier(value = "storageManager")
  private StorageManager storageManager;

  /**
   * 文件写入响应实体
   *
   * @param file 物理文件记录
   * @param response
   */
  private void read2OutputStream(RPanFile file, HttpServletResponse response) {
    try {
      storageManager.readFile(
          file.getStorageType(), file.getRealPath(), response.getOutputStream());
    } catch (Exception e) {
      log.error("文件写入响应实体失败", e);
      if (response.isCommitted()) {
        throw new IllegalStateException("文件传输中断", e);
      }
      response.reset();
      HttpUtil.addCorsResponseHeader(response);
      response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }
  }

  /**
   * 转移一个文件
   *
   * @param rPanUserFile
   * @param targetParentId
   */
  private void transferOne(RPanUserFile rPanUserFile, Long targetParentId, Long userId) {
    if (rPanUserFile.getParentId().equals(targetParentId)) {
      return;
    }
    Long previousParentId = rPanUserFile.getParentId();
    rPanUserFile.setParentId(targetParentId);
    rPanUserFile.setUpdateUser(userId);
    rPanUserFile.setUpdateTime(new Date());
    handleDuplicateFileName(rPanUserFile);
    // 修改文件信息
    if (rPanUserFileMapper.moveOwnedLiveFile(rPanUserFile, previousParentId)
        != CommonConstant.ONE_INT) {
      log.error("文件转移失败,文件名称为:{}", rPanUserFile.getFilename());
      throw new RPanException("文件转移失败");
    }
  }

  /**
   * 检查目标文件是不是子文件夹
   *
   * @param targetParentId
   * @param rPanUserFile
   * @return
   */
  private boolean checkIsChildFolder(Long targetParentId, RPanUserFile rPanUserFile) {
    if (checkIsFolder(rPanUserFile)) {
      List<RPanUserFile> allChildrenFile = Lists.newArrayList();
      findAllChildUserFile(allChildrenFile, rPanUserFile.getFileId());
      if (!CollectionUtils.isEmpty(allChildrenFile)) {
        List<Long> childFolderIdList =
            allChildrenFile.stream()
                .filter(this::checkIsFolder)
                .map(RPanUserFile::getFileId)
                .collect(Collectors.toList());
        if (childFolderIdList.contains(targetParentId)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * 拼装文件夹树
   *
   * @param folderList
   * @return
   */
  private List<FolderTreeNode> assembleFolderTree(List<RPanUserFile> folderList) {
    if (CollectionUtils.isEmpty(folderList)) {
      return Lists.newArrayList();
    }
    List<FolderTreeNode> folderTreeNodeList =
        folderList.stream()
            .map(FolderTreeNode::assembleFolderTreeNode)
            .collect(Collectors.toList());
    Map<Long, List<FolderTreeNode>> directoryTreeNodeParentGroup =
        folderTreeNodeList.stream().collect(Collectors.groupingBy(FolderTreeNode::getParentId));
    folderTreeNodeList.stream()
        .forEach(
            node -> {
              List<FolderTreeNode> children = directoryTreeNodeParentGroup.get(node.getId());
              if (!CollectionUtils.isEmpty(children)) {
                node.setChildren(children);
              }
            });
    return folderTreeNodeList.stream()
        .filter(node -> Objects.equals(CommonConstant.ZERO_LONG, node.getParentId()))
        .collect(Collectors.toList());
  }

  /**
   * 校验是不是文件夹
   *
   * @param fileId
   * @return
   */
  private boolean checkIsFolder(Long fileId) {
    return checkIsFolder(rPanUserFileMapper.selectByPrimaryKey(fileId));
  }

  /**
   * 校验是不是文件夹
   *
   * @param fileId
   * @param userId
   * @return
   */
  private boolean checkIsFolder(Long fileId, Long userId) {
    return checkIsFolder(rPanUserFileMapper.selectByFileIdAndUserId(fileId, userId));
  }

  /**
   * 校验是不是文件夹
   *
   * @param rPanUserFile
   * @return
   */
  private boolean checkIsFolder(RPanUserFile rPanUserFile) {
    if (Objects.isNull(rPanUserFile)) {
      throw new RPanException("文件不存在");
    }
    return Objects.equals(FileConstant.FolderFlagEnum.YES.getCode(), rPanUserFile.getFolderFlag());
  }

  /**
   * 校验是否是文件夹
   *
   * @param rPanUserFileVO
   * @return
   */
  private boolean checkIsFolder(RPanUserFileVO rPanUserFileVO) {
    if (Objects.isNull(rPanUserFileVO)) {
      throw new RPanException("文件不存在");
    }
    return Objects.equals(
        FileConstant.FolderFlagEnum.YES.getCode(), rPanUserFileVO.getFolderFlag());
  }

  /**
   * 查找并拼装子文件
   *
   * @param allChildUserFileList
   * @param parentUserFileId
   * @param newParentUserFileId
   * @param userId
   * @return
   */
  private void assembleAllChildUserFile(
      final List<RPanUserFile> allChildUserFileList,
      Long parentUserFileId,
      Long newParentUserFileId,
      Long userId) {
    List<RPanUserFile> childUserFileList =
        rPanUserFileMapper.selectAvailableListByParentId(parentUserFileId);
    if (CollectionUtils.isEmpty(childUserFileList)) {
      return;
    }
    childUserFileList.stream()
        .forEach(
            childUserFile -> {
              Long fileId = childUserFile.getFileId(), newFileId = IdGenerator.nextId();
              childUserFile.setParentId(newParentUserFileId);
              childUserFile.setUserId(userId);
              childUserFile.setFileId(newFileId);
              childUserFile.setCreateUser(userId);
              childUserFile.setCreateTime(new Date());
              childUserFile.setUpdateUser(userId);
              childUserFile.setUpdateTime(new Date());
              allChildUserFileList.add(childUserFile);
              if (checkIsFolder(childUserFile)) {
                assembleAllChildUserFile(allChildUserFileList, fileId, newFileId, userId);
              }
            });
  }

  /**
   * 查询文件的所有子节点
   *
   * @param allChildUserFileList
   * @param parentId
   */
  private void findAllChildUserFile(final List<RPanUserFile> allChildUserFileList, Long parentId) {
    List<RPanUserFile> childUserFileList = rPanUserFileMapper.selectAllListByParentId(parentId);
    if (CollectionUtils.isEmpty(childUserFileList)) {
      return;
    }
    allChildUserFileList.addAll(childUserFileList);
    childUserFileList.stream()
        .filter(rPanUserFile -> checkIsFolder(rPanUserFile))
        .forEach(
            rPanUserFile -> findAllChildUserFile(allChildUserFileList, rPanUserFile.getFileId()));
  }

  /**
   * 保存用户搜索关键字信息
   *
   * @param keyword
   * @param userId
   */
  private void saveUserSearchHistory(String keyword, Long userId) {
    iUserSearchHistoryService.save(keyword, userId);
  }

  /**
   * 拼装所有的与删除的用户文件信息列表
   *
   * @param fileIds
   * @return
   */
  private List<RPanUserFile> assembleAllToBeDeletedUserFileList(String fileIds) {
    List<RPanUserFile> rPanUserFileList =
        rPanUserFileMapper.selectListByFileIdList(StringListUtil.string2LongList(fileIds));
    final List<RPanUserFile> allChildUserFileList = Lists.newArrayList();
    rPanUserFileList.stream()
        .filter(rPanUserFile -> checkIsFolder(rPanUserFile))
        .forEach(
            rPanUserFile -> findAllChildUserFile(allChildUserFileList, rPanUserFile.getFileId()));
    rPanUserFileList.addAll(allChildUserFileList);
    return rPanUserFileList;
  }

  /**
   * 保存复制文件
   *
   * @param fileIds
   * @param targetParentId
   * @param userId
   * @return
   */
  private void doCopyUserFiles(
      String fileIds, Long targetParentId, Long userId, boolean fromShare) {
    validateSecUploadParent(targetParentId, userId);
    Set<Long> requested = new HashSet<>(StringListUtil.string2LongList(fileIds));
    List<RPanUserFile> roots =
        rPanUserFileMapper.selectListByFileIdList(new ArrayList<>(requested));
    if (roots.size() != requested.size()) throw new RPanException("源文件不存在");
    Map<Long, RPanUserFile> sources = new LinkedHashMap<>();
    Deque<RPanUserFile> queue = new ArrayDeque<>(roots);
    while (!queue.isEmpty()) {
      RPanUserFile source = queue.removeFirst();
      if (sources.putIfAbsent(source.getFileId(), source) != null) continue;
      if (!fromShare && !Objects.equals(source.getUserId(), userId)) {
        throw new RPanException("不能复制其他用户的文件");
      }
      if (checkIsFolder(source))
        queue.addAll(rPanUserFileMapper.selectAvailableListByParentId(source.getFileId()));
    }
    Set<Long> realIds = physicalIds(sources.values());
    fileLifecycleService.lockActiveFiles(realIds);
    for (RPanUserFile before :
        sources.values().stream()
            .sorted(Comparator.comparing(RPanUserFile::getFileId))
            .collect(Collectors.toList())) {
      RPanUserFile current = rPanUserFileMapper.selectByIdForUpdate(before.getFileId());
      if (!sameReference(before, current)
          || !isCurrentSourceAvailable(before.getFileId(), before.getUserId())) {
        throw new RPanException("源文件已变化或不可用，请刷新后重试");
      }
    }
    // 保留原项目复制树和重名处理；其读取的源快照已在持锁状态下核对。
    complementToBeCopiedFileInfoList(roots, targetParentId, userId);
    if (!physicalIds(roots).equals(realIds)) throw new RPanException("源目录已变化，请刷新后重试");
    long charged = 0;
    Map<Long, Long> sizes = new HashMap<>();
    for (Long realId : realIds)
      sizes.put(realId, UserSpaceService.fileBytes(iFileService.getFileDetail(realId)));
    for (RPanUserFile record : roots) {
      long bytes = checkIsFolder(record) ? 0 : sizes.get(record.getRealFileId());
      record.setSpaceBytes(bytes);
      charged = Math.addExact(charged, bytes);
    }
    space.charge(userId, charged);
    if (rPanUserFileMapper.insertBatch(roots) != roots.size()) throw new RPanException("文件复制失败");
  }

  private Set<Long> physicalIds(Collection<RPanUserFile> files) {
    return files.stream()
        .filter(f -> !checkIsFolder(f))
        .map(RPanUserFile::getRealFileId)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private boolean sameReference(RPanUserFile before, RPanUserFile current) {
    return current != null
        && Objects.equals(before.getUserId(), current.getUserId())
        && Objects.equals(before.getParentId(), current.getParentId())
        && Objects.equals(before.getRealFileId(), current.getRealFileId())
        && Objects.equals(before.getFolderFlag(), current.getFolderFlag())
        && Objects.equals(before.getDelFlag(), current.getDelFlag());
  }

  private boolean isCurrentSourceAvailable(Long id, Long owner) {
    Set<Long> visited = new HashSet<>();
    boolean leaf = true;
    while (id != null && id > 0 && visited.add(id)) {
      RPanUserFile file = rPanUserFileMapper.selectByIdForUpdate(id);
      if (file == null
          || !Objects.equals(file.getUserId(), owner)
          || !Objects.equals(file.getDelFlag(), FileConstant.DelFlagEnum.NO.getCode())
          || (!leaf && !checkIsFolder(file))) return false;
      if (Objects.equals(file.getParentId(), CommonConstant.ZERO_LONG)) return true;
      id = file.getParentId();
      leaf = false;
    }
    return false;
  }

  /**
   * 递归查找所有子文件信息
   *
   * @param allRPanUserFileVOList
   * @param rPanUserFileVO
   */
  private void findAllAvailableChildUserFile(
      List<RPanUserFileVO> allRPanUserFileVOList, RPanUserFileVO rPanUserFileVO) {
    if (!checkIsFolder(rPanUserFileVO)) {
      return;
    }
    List<RPanUserFileVO> rPanUserFileVOList =
        rPanUserFileMapper.selectAvailableRPanUserFileVOListByParentId(rPanUserFileVO.getFileId());
    if (CollectionUtils.isEmpty(rPanUserFileVOList)) {
      return;
    }
    allRPanUserFileVOList.addAll(rPanUserFileVOList);
    rPanUserFileVOList.stream()
        .forEach(
            rPanUserFileVOItem ->
                findAllAvailableChildUserFile(allRPanUserFileVOList, rPanUserFileVOItem));
  }

  /**
   * 根据用户id和文件id查询对应的文件信息
   *
   * @param fileId
   * @param userId
   * @return
   */
  private RPanUserFile getRPanUserFileByFileIdAndParentId(Long fileId, Long userId) {
    RPanUserFile originalUserFileInfo = rPanUserFileMapper.selectByPrimaryKey(fileId);
    if (Objects.isNull(originalUserFileInfo)) {
      throw new RPanException("该用户没有此文件");
    }
    if (!Objects.equals(originalUserFileInfo.getUserId(), userId)) {
      throw new RPanException("该用户没有此文件");
    }
    if (Objects.equals(FileConstant.DelFlagEnum.YES.getCode(), originalUserFileInfo.getDelFlag())) {
      throw new RPanException("该用户没有此文件");
    }
    return originalUserFileInfo;
  }

  /**
   * 校验目标文件夹是否合法 1、 不是选中的文件夹 2、 不是选中文件夹的子文件夹
   *
   * @param targetParentId
   * @param fileIds
   * @return
   */
  private boolean checkTargetParentIdAvailable(Long targetParentId, String fileIds) {
    List<Long> fileIdList = StringListUtil.string2LongList(fileIds);
    if (fileIdList.contains(targetParentId)) {
      return false;
    }
    for (Long fileId : fileIdList) {
      if (checkIsChildFolder(targetParentId, rPanUserFileMapper.selectByPrimaryKey(fileId))) {
        return false;
      }
    }
    return true;
  }

  /**
   * 递归查询所有有效的子节点id
   *
   * @param allAvailableFileId
   * @param fileId
   */
  private void findAllAvailableFileIdByFileId(List<Long> allAvailableFileId, Long fileId) {
    if (!checkIsFolder(fileId)) {
      return;
    }
    List<Long> childrenFileIds = rPanUserFileMapper.selectAvailableFileIdListByParentId(fileId);
    if (CollectionUtils.isEmpty(childrenFileIds)) {
      return;
    }
    allAvailableFileId.addAll(childrenFileIds);
    childrenFileIds.stream()
        .forEach(
            childrenFileId -> findAllAvailableFileIdByFileId(allAvailableFileId, childrenFileId));
  }

  /**
   * 校验当前文件和所有父文件夹是否均正常
   *
   * @param fileId
   * @return
   */
  private boolean checkUpFileAvailable(Long fileId) {
    RPanUserFile rPanUserFile = rPanUserFileMapper.selectByPrimaryKey(fileId);
    if (rPanUserFile.getDelFlag().equals(FileConstant.DelFlagEnum.YES.getCode())) {
      return false;
    }
    if (rPanUserFile.getParentId().equals(CommonConstant.ZERO_LONG)) {
      return true;
    }
    return checkUpFileAvailable(rPanUserFile.getParentId());
  }
}
