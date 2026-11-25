package com.rubin.rpan.modules.file;

import org.junit.Assume;

public class UploadCompletionMySqlTest extends UploadCompletionTest {
  @Override
  protected boolean mysql() {
    Assume.assumeTrue(
        "Requires disposable local MySQL database",
        System.getProperty("cleanup.mysql.url") != null);
    return true;
  }
}
