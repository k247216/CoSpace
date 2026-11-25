package com.rubin.rpan.modules.file.upload.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "rpan.upload")
public class UploadProperties {
  private boolean ossDirectEnabled;

  public boolean isOssDirectEnabled() {
    return ossDirectEnabled;
  }

  public void setOssDirectEnabled(boolean ossDirectEnabled) {
    this.ossDirectEnabled = ossDirectEnabled;
  }

  @Min(1)
  @Max(16777216)
  private int chunkSize = 2097152;

  @Min(0)
  @Max(3221225472L)
  private long maxFileSize = 3221225472L;

  @Min(1)
  @Max(10000)
  private int maxChunks = 10000;

  @Min(1000)
  @Max(86400000)
  private long taskTtlMs = 86400000;

  private String stagingPath;
  @Valid private Gc gc = new Gc();

  public Gc getGc() {
    return gc;
  }

  public void setGc(Gc v) {
    gc = v;
  }

  public static class Gc {
    private boolean enabled = true;

    @Min(1)
    @Max(1000)
    private int batchSize = 50;

    @Min(60000)
    private long finalizingRetentionMs = 604800000L;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean v) {
      enabled = v;
    }

    public int getBatchSize() {
      return batchSize;
    }

    public void setBatchSize(int v) {
      batchSize = v;
    }

    public long getFinalizingRetentionMs() {
      return finalizingRetentionMs;
    }

    public void setFinalizingRetentionMs(long v) {
      finalizingRetentionMs = v;
    }
  }

  public int getChunkSize() {
    return chunkSize;
  }

  public void setChunkSize(int v) {
    chunkSize = v;
  }

  public long getMaxFileSize() {
    return maxFileSize;
  }

  public void setMaxFileSize(long v) {
    maxFileSize = v;
  }

  public int getMaxChunks() {
    return maxChunks;
  }

  public void setMaxChunks(int v) {
    maxChunks = v;
  }

  public long getTaskTtlMs() {
    return taskTtlMs;
  }

  public void setTaskTtlMs(long v) {
    taskTtlMs = v;
  }

  public String getStagingPath() {
    return stagingPath;
  }

  public void setStagingPath(String v) {
    stagingPath = v;
  }
}
