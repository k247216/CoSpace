package com.rubin.rpan.storage.local.launch;

import com.rubin.rpan.storage.local.config.LocalStorageConfig;
import com.rubin.rpan.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 初始化本地文件根目录；任务暂存目录由新上传链路按需创建 */
@Component(value = "uploadFolderInitializer")
@ConditionalOnProperty(
    prefix = "rpan.storage.processor",
    name = "type",
    havingValue = "com.rubin.rpan.storage.local.LocalStorageProcessor")
public class UploadFolderInitializer implements RPanLaunchedProcessor {

  private static final Logger log = LoggerFactory.getLogger(RPanLaunchedProcessor.class);

  @Autowired
  @Qualifier(value = "localStorageConfig")
  private LocalStorageConfig localStorageConfig;

  /** 处理初始化任务 */
  @Override
  public void process() {
    FileUtil.createFolder(localStorageConfig.getRootFilePath());
    log.info("文件根目录初始化完成！");
  }
}
