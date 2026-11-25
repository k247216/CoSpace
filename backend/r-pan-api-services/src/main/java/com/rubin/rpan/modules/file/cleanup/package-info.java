/**
 * 业务文件可靠清理：service 执行清理任务，mq 负责 Outbox 投递与 RocketMQ 通信， storage 删除实际存储内容，config 配置重试、租约与调度。
 * 上传产物回收位于 file.upload.job，和本包的业务文件删除生命周期分开。
 */
package com.rubin.rpan.modules.file.cleanup;
