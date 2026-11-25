package com.rubin.rpan.modules.user.controller;

import com.rubin.rpan.common.annotation.NeedLogin;
import com.rubin.rpan.common.response.R;
import com.rubin.rpan.modules.user.service.IUserSearchHistoryService;
import com.rubin.rpan.modules.user.vo.RPanUserSearchHistoryVO;
import com.rubin.rpan.util.UserIdUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 项目用户搜索历史相关rest接口返回 */
@RestController
@Tag(name = "用户搜索历史")
public class UserSearchHistoryRestController {

  @Autowired
  @Qualifier(value = "userSearchHistoryService")
  private IUserSearchHistoryService iUserSearchHistoryService;

  /**
   * 获取用户最新的搜索历史列表，默认十条
   *
   * @return
   */
  @Operation(summary = "获取用户最新的搜索历史列表，默认十条", description = "该接口提供了获取用户最新的搜索历史列表，默认十条的功能")
  @GetMapping("user/search/histories")
  @NeedLogin
  public R<List<RPanUserSearchHistoryVO>> list() {
    return R.data(iUserSearchHistoryService.list(UserIdUtil.get()));
  }
}
