package com.rubin.rpan.storage.local.config;

import com.rubin.rpan.util.FileUtil;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component(value = "localStorageConfig")
@ConfigurationProperties(prefix = "rpan.storage.local")
@ConditionalOnProperty(
    prefix = "rpan.storage.processor",
    name = "type",
    havingValue = "com.rubin.rpan.storage.local.LocalStorageProcessor")
public class LocalStorageConfig {
  private String rootFilePath = FileUtil.generateDefaultRootFolderPath();

  public String getRootFilePath() {
    return rootFilePath;
  }

  public void setRootFilePath(String rootFilePath) {
    this.rootFilePath = rootFilePath;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    return Objects.equals(rootFilePath, ((LocalStorageConfig) o).rootFilePath);
  }

  @Override
  public int hashCode() {
    return Objects.hash(rootFilePath);
  }

  @Override
  public String toString() {
    return "LocalStorageConfig{rootFilePath='" + rootFilePath + "'}";
  }
}
