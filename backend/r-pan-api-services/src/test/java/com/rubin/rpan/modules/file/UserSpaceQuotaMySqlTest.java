package com.rubin.rpan.modules.file;

import org.junit.*;

public class UserSpaceQuotaMySqlTest extends UserSpaceQuotaTest {
  @Override
  protected boolean mysql() {
    return true;
  }

  @Before
  @Override
  public void setUp() throws Exception {
    Assume.assumeTrue(
        "Requires isolated disposable MySQL schema",
        System.getProperty("cleanup.mysql.url") != null);
    super.setUp();
  }
}
