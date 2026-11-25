package com.rubin.rpan.common.annotation;

import java.lang.annotation.*;

/** 接口需要分享码url标识注解 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface NeedShareCode {}
