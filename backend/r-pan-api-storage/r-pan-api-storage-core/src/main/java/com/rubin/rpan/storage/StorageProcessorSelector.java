package com.rubin.rpan.storage;

import java.io.IOException;
import java.util.Objects;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/** 存储处理器选择器 */
@Component(value = "storageProcessorSelector")
public class StorageProcessorSelector implements ApplicationContextAware {

  private ApplicationContext applicationContext;

  private StorageProcessor storageProcessor;

  @Value("${rpan.storage.processor.type}")
  private Class<? extends StorageProcessor> storageProcessorType;

  @Value("${rpan.storage.legacy-type:${rpan.storage.processor.type}}")
  private String legacyType;

  @Override
  public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
    this.applicationContext = applicationContext;
    if (Objects.isNull(storageProcessorType)) {
      throw new RuntimeException(
          "the storage processor type can not be null!<rpan.storage.processor.type>");
    }
    this.storageProcessor = this.applicationContext.getBean(storageProcessorType);
    if (Objects.isNull(this.storageProcessor)) {
      throw new RuntimeException(
          "the storage processor type:"
              + storageProcessorType.getSimpleName()
              + " can not be found!");
    }
  }

  /**
   * 选择StorageProcessor实现类
   *
   * @return
   */
  public StorageProcessor select() {
    return storageProcessor;
  }

  public StorageProcessor selectForFile(String storageType) throws IOException {
    String type = storageType == null ? legacyType : storageType;
    for (String beanName : applicationContext.getBeanNamesForType(StorageProcessor.class)) {
      Class<?> beanType = applicationContext.getType(beanName);
      if (beanType != null
          && ClassUtils.getUserClass(beanType).getName().equals(type)
          && !StorageManager.class.isAssignableFrom(beanType)) {
        return applicationContext.getBean(beanName, StorageProcessor.class);
      }
    }
    throw new IOException("File storage backend is not configured");
  }
}
