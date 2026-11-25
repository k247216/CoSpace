package com.rubin.rpan.modules.file.upload.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
    prefix = "rpan.upload.gc",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class UploadSchedulingConfig {}
