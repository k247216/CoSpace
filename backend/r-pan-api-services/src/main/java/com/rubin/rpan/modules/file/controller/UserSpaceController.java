package com.rubin.rpan.modules.file.controller;

import com.rubin.rpan.common.annotation.NeedLogin;
import com.rubin.rpan.common.response.R;
import com.rubin.rpan.modules.file.quota.model.UserSpaceAccount;
import com.rubin.rpan.modules.file.quota.service.UserSpaceService;
import com.rubin.rpan.util.UserIdUtil;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("file/space")
public class UserSpaceController {
  private final UserSpaceService space;

  public UserSpaceController(UserSpaceService space) {
    this.space = space;
  }

  @GetMapping
  @NeedLogin
  public R<UserSpaceAccount> current() {
    return R.data(space.view(UserIdUtil.get()));
  }
}
