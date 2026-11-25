/**
 * 可恢复上传：service 编排请求，model 定义请求/结果与状态，storage 处理分片和进度， concurrent 协调单 JVM 并发，job 回收上传产物，config
 * 配置参数与调度，limit 保护初始化频率。 持久实体和 Mapper 沿用 file.entity、file.dao，不重复定义。
 */
package com.rubin.rpan.modules.file.upload;
