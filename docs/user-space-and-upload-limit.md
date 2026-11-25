# 用户逻辑空间与上传初始化限流

2026-11-17。容量以 MySQL 为权威；Redis Lua 只限制初始化尝试频率。上传仍限单实例、本地文件系统。两者不构成 Redis/MySQL 分布式事务。

## 1. 业务规则与数据

每条逻辑文件记录按实际字节大小计费，文件夹为 0；相同 realFileId 的两条记录计两份空间。回收站继续计费，只有彻底删除引用才释放已用。移动、重命名和恢复不改变容量。用户容量释放不等待 MQ 删除磁盘。

`r_pan_user_space`：每个用户一行，保存 total_bytes、used_bytes、reserved_bytes；约束为非负且 used + reserved <= total。默认新用户 10 GiB，注册事务内同时创建账户。账户不存在时明确报错，不按零用量偷偷创建，避免漏算历史文件。

`r_pan_user_file.space_bytes`：逻辑记录的计费快照。复制和删除使用稳定字节数，不解析 file_size_desc。创建引用时以已锁定的物理元数据计算，不相信秒传请求中的大小作为扣费数值。

`r_pan_upload_reservation`：upload_id 主键，另有 UNIQUE(user_id, request_id)，记录 bytes、RESERVED / COMMITTED / RELEASED 和时间。完成后保留结算历史；以后删除用户文件释放的是 used，不把已 COMMITTED 的上传预占改成 RELEASED。这是空间账户及预占明细，不是完整的逐笔财务流水。

## 2. 按入口阅读调用链

类均在 services 模块的 `com.rubin.rpan.modules` 下。

| 入口 | 调用链 | 同事务提交的数据 |
| --- | --- | --- |
| 注册 | user.controller.UserRestController → UserServiceImpl.register → UserSpaceService.createAccount | 用户、空间账户、根目录 |
| 初始化 | file.controller.UploadTaskController.initialize → UploadTaskService.initialize → UploadInitRateLimiter.check → TransactionTemplate → UserSpaceService.lock / findRequest / reserve → tasks.insert | 预占计数、预占明细、上传任务 |
| 接收分片 | UploadTaskController.receive → UploadTaskService.receive → UploadChunkStorage.receive → Bitmap | 不修改容量，磁盘 IO 不包数据库长事务 |
| 完成 | UploadCompletionService.complete → 第一段事务登记 FINALIZING → assemble → 第二段 register → UserSpaceService.commit | 两张文件表、reserved 转 used、COMMITTED、COMPLETED |
| 校验失败 | complete 捕获 IntegrityException → 短事务锁账户、任务 → release / recordError | RELEASED、reserved 减少、FAILED |
| 取消 | POST /file/upload/tasks/{id}/cancel → UploadTaskService.cancel → 任务写锁 → 短事务 | EXPIRED、RELEASED、reserved 减少 |
| 过期回收 | upload.job.UploadTaskCleaner.cleanOne → 短事务 expire / release → 事务外清盘 → 短事务登记产物清理结果 | 废弃状态与空间释放先提交；磁盘失败不继续占用户预占 |
| 秒传 | FileRestController.secUpload → UserFileServiceImpl.secUpload → saveUserFile → UserSpaceService.charge | 用户引用和 used 增加 |
| 复制、分享转存 | UserFileServiceImpl.copy / saveBatch → doCopyUserFiles → 累计实际新增记录 → charge → insertBatch | 整批引用和容量变化；不足则全部回滚 |
| 彻底删除 | physicalDeleteUserFiles → 锁定并复核引用 → physicalDeleteBatch → releaseUsed → scheduleIfUnreferenced | 引用删除、used 释放、必要时物理 DELETING 和 MQ 清理任务 |
| 查询空间 | GET /file/space → UserSpaceController.current → UserSpaceService.view | 登录用户自己的账户快照 |

## 3. 事务、锁与恢复

容量写方法采用 MANDATORY 并显式检查事务，加入现有业务事务。计费入口先锁当前用户账户，再沿已有协议锁任务、物理文件、用户引用。账户锁只在短事务内持有，不跨分片写入、合并或磁盘删除。

预占和直接计费使用条件 UPDATE，余额不足影响 0 行并抛出业务码 4101。结算要求预占当前为 RESERVED；同一目标状态的重试直接返回，已经释放后不能再提交为已用。禁止把负数强行截成 0 来掩盖账目错误。

初始化可携带 UUID requestId。前端在发请求前按登录会话和文件参数保存它；响应丢失后先重放初始化，不先秒传。账户锁和唯一键让同一用户相同 requestId 只生成一个 uploadId、一份预占。重用 requestId 但改变目录、文件名、大小或 MD5 会拒绝。没有 requestId 的旧调用者由服务端生成一个，但无法获得跨请求初始化幂等保证。

FINALIZING 的普通存储或入库异常保留预占，重试仍可成功；整文件校验失败才立即释放。取消与 complete 共用任务写锁：哪个阶段先完成就按其状态处理，COMPLETED 不能再通过取消接口释放空间。取消复用 EXPIRED 终态，不再引入一套清理状态；磁盘和 Bitmap 留给 Cleaner 幂等回收。

## 4. Redis Lua 令牌桶

位置：`upload.limit.UploadInitRateLimiter`、`resources/lua/upload-init-limit.lua`。

key 为 `rpan:upload:init-limit:{userId}`，身份只取登录上下文。默认允许突发 10 次，每秒补 1 次。仅初始化调用 check；分片和 complete 不消耗令牌。重放初始化也属于一次尝试。

脚本用 Redis TIME，采用整数时间额度：每个令牌消耗一个补充间隔的 credit，避免浮点累计误差。读取、补充、判断、扣除、保存、TTL 在短 Lua 内完成。TTL 不短于从空桶补满所需时间；检测时钟倒退时暂停补充，不向后移动逻辑时间。容量和补充间隔有边界校验，脚本不循环扫描。

额度不足：HTTP 429、业务码 4429、Retry-After 秒数。Redis 访问/脚本返回异常：HTTP 503、业务码 4503，记录 WARN；耗尽记录 INFO。CorsFilter 暴露 Retry-After。前端解析响应体和等待时间，显示倒计时并禁用即时重试，不自动无限重试。

后续数据库失败可以消耗令牌，因为限制的是初始化尝试。不给令牌做跨系统退款。Redis 不可用时拒绝新建任务；这不等于已有上传都能脱离 Redis，receive 原本就依赖 Bitmap。

## 5. 产品入口

侧栏 UserSpace 组件展示总额、已用、预占、可用，提醒回收站计费；在文件业务请求、上传结束、取消后刷新，忽略分片请求产生的高频刷新。上传窗口支持等待限流后重试，以及停止派发、等待在途分片后取消；合并请求已经发出时不再提供即时取消承诺。

`VUE_APP_API_BASE_URL` 可覆盖前端 API 地址，默认仍为本机 8080。隔离验收使用前端 7002 和后端 18080，避免改动用户开发端口。

上传窗口只展示文件级百分比，以及准备文件、检查文件、准备上传、正在上传、正在处理文件和上传完成等状态；不向用户展示分片编号、分片数量或合并细节。后台成功完成后才显示100%。

## 6. 增量迁移

已有库使用 `20261117_user_space_schema.sql` 和 `20261117_user_space_backfill.sql`；不要拿包含 DROP TABLE 的 db.sql 迁移已有数据。db.sql 仅用于新建空库。

```sh
python3 scripts/migrate-user-space.py
# 停止后端写入后：
python3 scripts/migrate-user-space.py --apply
```

脚本默认只读，只连接本机 MySQL；密码通过环境变量传给 mysql，不进入命令行。执行前检查缺失/非法文件大小，拒绝部分应用的 schema，检查后端端口没有监听。回填包括回收站和历史上传任务：开放任务 RESERVED，完成任务 COMMITTED，失败/过期任务 RELEASED。初始总额至少覆盖历史 used + reserved；默认值跟随配置。回填 DML 在事务中执行，之后核对两种计数、缺失账户和非负约束。重复执行只核对已回填账户，不覆盖运行中的账户余额。

这要求迁移期间所有写入方停用；端口检查只是本地辅助检查，不等于能自动阻止其他脚本或其他实例写库。遇到非法大小先修复来源数据，不默认当成零。

## 7. 验收与边界

`UserSpaceQuotaTest` 与 MySQL 子类覆盖：两个 800 字节任务争抢 1000 字节余量；同 requestId 并发初始化；创建回滚；完成重复结算；入库回滚保留预占；FAILED/EXPIRED 释放；磁盘/Redis清理失败后的幂等释放；取消与完成竞争；取消 FINALIZING 的正式遗留文件；秒传/复制逐记录计费；回收站/恢复不释放；彻底删除与共享物理内容保护；递归复制不足整批回滚；分享转存计费到接收人。

`UploadRateLimitTest` 验证 429/503 区分，以及拒绝发生在任务、预占创建之前。`UploadRateLimitRedisTest` 使用本机 Redis DB15、随机用户 key，两个独立客户端共用 3 个令牌并发发 100 次请求，只通过 3 次；另一用户不受影响；补充和空闲过期后恢复。不会 FLUSHDB 或停止 Redis。

前端 `npm run test:upload` 包含初始化响应丢失、刷新后重放 requestId、容量不足不继续、429不自动重试、取消等待在途请求，再加已有断点续传及并发分片场景。

目前不提供额度套餐、完整逐笔流水、未知磁盘文件自动清理、全局带宽限速或多实例上传支持。容量事务与 Redis 限流可以协调跨连接请求，但不改变上传字节仍在单实例本地磁盘的部署边界。未进行生产容量压测，不提供虚构性能收益。

本轮验收：112项本地后端相关测试、43项隔离真实MySQL/Redis测试、17项前端编排检查通过；生产构建通过（原有3项包体积告警）。本机已有2个用户完成增量回填与账目核对。另在临时库验证真实旧schema迁移（回收站、开放任务及重复执行），启动完整Spring应用验证注册、初始化重放、取消、分片完成、秒传、空间不足和429。Playwright使用独立前端7002/后端18080，验证5MiB预占增长、取消后归零、完成后已用增长、真实429倒计时禁用重试。截图位于忽略目录output/playwright/quota-*.png；临时服务、测试库和本次Redis键验收后清理。
