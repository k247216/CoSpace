# 贡献约定

优先修复可复现的问题，保持文件、上传、容量、清理、存储和团队职责清楚。存储 IO 不放进长数据库事务；权限和容量的写操作采用既定锁顺序，不为格式统一改变并发语义。

提交前运行 README 中的快速测试、前端构建和格式检查。涉及 SQL、权限、上传完成或容量时，需要真实 MySQL/Redis/MQ 集成回归；涉及存储语义时补充真实 Bucket 验证，注明没有覆盖的范围。

Java 使用固定版本 google-java-format：`bash scripts/format-java.sh --write`；前端使用锁定的 Prettier：`npm --prefix frontend run format`。命名直接表达业务含义，避免重复状态字段、无用途抽象和捕获异常后静默成功。

提交说明写清触发条件、行为变化、验证步骤和限制。不提交 .env、密钥、数据库备份、用户内容、含令牌的 fixture 或运行日志。提交前检查 `git diff --cached`，保留 LICENSE/NOTICE 和第三方资产授权。
