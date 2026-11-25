# CoSpace 存储接入基础

第一步让物理文件元数据记录自己的存储类型。上传完成时，物理文件继承上传任务的 `storage_type`。个人下载、分享下载和预览都在原有鉴权后读取对应适配器。彻底删除时，Outbox 任务快照同时记录存储类型和定位符。消费者领取任务时核对两者，发现不一致则转人工处理。

## 旧数据兼容

旧物理记录没有存储类型。迁移只新增可空列。空值由 `rpan.storage.legacy-type` 决定，默认沿用旧的全局适配器设置。部署前如果更改默认适配器，请显式保留老文件的真实存储类型，并确保对应适配器可用。找不到适配器时返回服务不可用，避免按另一个存储的路径读取。

已有库先执行 [20261123_file_storage_type.sql](../backend/r-pan-api-services/src/main/resources/sql/migrations/20261123_file_storage_type.sql)，再部署新版本。新库使用包含该字段的 `db.sql`。本次代码不移动任何旧文件，也不猜测旧对象属于哪个存储。

## 私有 Bucket 接入

浏览器继续调用现有分片接口。应用临时保存分片，汇总并校验整文件 MD5。上传任务先持久化 `FINALIZING` 和稳定的 `s3://bucket/cospace/files/<uploadId>`，再在数据库事务之外通过带 `If-None-Match: *` 的 `PutObject` 写入成品。文件登记、用户引用、配额结算和任务完成在同一事务内提交。若对象已写入而登记失败，重试先检查对象大小和 MD5 元数据，再完成登记。已完成任务的重试返回原结果。

应用启动前创建私有 Bucket。S3 适配器默认关闭。启用时可设置以下环境变量：

```dotenv
RPAN_STORAGE_S3_ENABLED=true
RPAN_STORAGE_S3_BUCKET=your-private-bucket
RPAN_STORAGE_S3_REGION=us-east-1
RPAN_UPLOAD_STORAGE_TYPE=com.rubin.rpan.storage.s3.S3StorageProcessor
```

使用兼容端点时另设 `RPAN_STORAGE_S3_ENDPOINT`，按服务的寻址方式设置 `RPAN_STORAGE_S3_PATH_STYLE=true`。凭证由 AWS SDK 默认凭证链读取。应用需要对象前缀 `cospace/files/` 下的 `GetObject`、`PutObject`、`DeleteObject` 权限，以及 Bucket 的 `ListBucket` 权限，以便 AWS S3 对不存在的对象返回 404。若希望删除直接回收数据，使用未启用版本控制的专用 Bucket。配置切换时保持已登记对象的 Bucket 和端点可达。

回收站彻底删除沿用 Outbox 和 RocketMQ，执行器按文件登记的存储类型路由。取消上传立即释放预留配额。已有对象定位的取消或失败任务等待 30 分钟再检查和删除远端对象，为超时后的在途写入留出时间。清理前复核对象键、登记状态和对象元数据。

当前部署形态为单应用实例。分片和合并产物需要持久化本地目录，应用负责上传和下载的流量。日后接入浏览器直传时，CoSpace 任务 ID 和 S3 multipart upload ID 应分别持久化，并验证预签名过期、跨域、分片查询和取消。

## 验证记录

- 存储类型迁移使用隔离 MySQL 库验证。实时 HTTP 上传和下载覆盖已登记类型、旧记录空值回退、未配置适配器返回 503。
- 对象存储使用本机私有 RustFS Bucket 及独立 Redis、RocketMQ、MySQL 测试库。上传 5 MiB 文件后，通过临时 MySQL 触发器让物理文件登记失败。确认对象存在、任务保持 `FINALIZING`、配额仍只预留。移除触发器后重试两次，确认只有一条物理文件记录、配额只结算一次，下载内容逐字节一致。
- 在同一环境验证回收站彻底删除经 RocketMQ 将对象删除并释放配额。另行模拟登记失败后取消上传，确认预留立即释放，清理任务在跨过 30 分钟阈值后移除对象。测试通过调整临时库任务时间验证阈值。
- 后端回归覆盖 190 项。全量运行中 188 项通过，两个 RocketMQ 用例遇到临时 NameServer 连接问题。使用独立端口单独复跑这两个用例，均通过。

参考 [S3 条件写入](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html) 和 [PutObject API](https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html)。
