# 部署与排障

## 环境和入口

应用通过 Compose 运行，MySQL、Redis 和 RocketMQ 不发布到公网。Nginx 仅监听主机回环端口；公网由独立 Caddy 项目提供 80/443，内部 Actuator 和接口文档入口在边缘层屏蔽。

```bash
# 在服务器的独立发布目录解包后生成配置
python3 scripts/init-env.py --output /安全目录/cospace.env --port 18080
# 使用解包目录下的 compose.yaml，配置不放进仓库
# docker compose --env-file /安全目录/cospace.env -f /发布目录/compose.yaml up -d
```

构建发布包：先 `mvn -f backend/pom.xml -pl r-pan-api-services -am -DskipTests package`，再 `bash scripts/package-azure-release.sh`。输出包括 JAR、前端、Compose、SQL 与空凭证示例；SHA256 用于传输后核对。新数据库使用打包的完整 schema；已有数据库必须先备份，再按设计文档核对需要的增量迁移，不能重复初始化整个库。

更新保留原 `.env`、持久卷与可回滚的上一版发布目录。先校验 Compose 配置，再启动应用和 Web；核对健康、文件下载和容量后切换 current。不要为更新应用删除数据库卷。代码回滚不能自动撤销已提交的数据迁移，应使用向前兼容的增量方案。

## 公开体验与 OSS 联调

公开体验地址：[https://cospace-demo.duckdns.org/](https://cospace-demo.duckdns.org/)。

| 环境 | Compose 项目 | HTTP 入口 | 存储 |
| --- | --- | --- | --- |
| 公开体验 | cospace-demo | 服务器回环 18081，经 Caddy 公开 | 独立本地存储卷 |
| OSS 联调 | cospace | 服务器回环 18080，经 SSH 隧道访问 | 私有 OSS Bucket |

两个环境使用各自的数据库、Redis、MQ、容量账目、会话密钥和存储卷。公开体验关闭 OSS 适配器与直传，应用不注入 OSS 凭证；域名和原 IP 的 HTTPS 入口均代理到公开体验。原 OSS 文件和账户保留在联调环境，体验账户独立注册。

公开体验复用同一个发布包，先用 `scripts/init-env.py` 为体验目录生成配置，设置 `COSPACE_SHARE_PREFIX=https://cospace-demo.duckdns.org/share/`，再用 `docker compose -p cospace-demo --env-file /体验目录/.env -f /发布目录/compose.yaml up -d` 启动。默认配置为本地存储。Caddy 的 `COSPACE_UPSTREAM` 设置为 `127.0.0.1:18081`。

访问私下 OSS 环境时，在本机建立 SSH 转发：

```bash
ssh -N -L 18080:127.0.0.1:18080 你的SSH主机别名
```

随后打开 `http://127.0.0.1:18080`。更新发布时分别指定项目名和各自的私有配置文件。

## OSS 配置

首选一个真实私有 Bucket，默认端点使用其所属地域的公网 HTTPS Endpoint。跨云服务器不能使用阿里云 VPC 内网 Endpoint。应用角色或专用 RAM 用户只授予业务对象前缀需要的写、读、删除、ListParts、AbortMultipartUpload 权限；不要给予整个账号的管理权限。

在服务器的私有环境配置中设置：

```dotenv
COSPACE_OSS_ENABLED=true
COSPACE_OSS_REGION=cn-guangzhou
COSPACE_OSS_BUCKET=你的私有Bucket
COSPACE_OSS_ENDPOINT=https://oss-cn-guangzhou.aliyuncs.com
COSPACE_OSS_CNAME=false
COSPACE_UPLOAD_STORAGE_TYPE=com.rubin.rpan.storage.oss.OssStorageProcessor
COSPACE_OSS_DIRECT_ENABLED=true
# OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET 通过环境注入
```

存储类型必须与应用注册的 Processor 一致；具体标识可从 `OssStorageProcessor.java` 核对。更改默认存储不会搬迁已有对象，物理记录中的存储类型仍决定读取和清理路由。

浏览器直传还需 Bucket CORS：来源填写实际页面 origin（含协议和非默认端口），允许 PUT/GET/HEAD，允许签名请求所需头，暴露 ETag 和 x-oss-request-id。来源不应填写后端 API 的另一个域名。不要把 Bucket 设为公共读来排查跨域。

Bucket 生命周期只对业务前缀配置清理未完成分片；已有环境采用 8 天规则。应用凭证不必拥有读取或修改 Bucket 生命周期的权限，管理员在控制台核对即可。

参考：[OSS CORS](https://www.alibabacloud.com/help/zh/oss/user-guide/configure-cross-origin-resource-sharing/)。

## HTTPS

`deploy/public/compose.yaml` 为独立边缘项目。将其与 Caddyfile 放在服务器上，在同目录 `.env` 中填写 `COSPACE_PUBLIC_HOST`（域名）和 `COSPACE_PUBLIC_IP`（服务器公网 IP），然后启动。配置示例见 `deploy/public/.env.example`。域名与 IP 均提供 HTTPS 访问。80/443 必须在云安全组和主机防火墙都可达。Caddy 配置使用 ACME shortlived profile，TLS 数据卷需要持久化；不要删除证书卷后反复申请证书。

公网 IP 证书与配置参考：[Let's Encrypt IP certificates](https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability)、[Caddy TLS](https://caddyserver.com/docs/caddyfile/directives/tls)。证书签发成功、回环访问成功、普通访客网络访问成功是三项不同检查。

## 排查顺序

| 现象 | 先查证据 | 处理与复核 |
| --- | --- | --- |
| HTTP 可达，HTTPS 超时 | ss 的 443 监听、UFW、云 NSG 有效规则、服务器是否收到 SYN | 修正实际绑定安全组中的 TCP443；重新用移动网络访问 |
| 页面 502 | Nginx upstream 日志、应用健康、容器状态 | 区分后端不可用和本地 SSH 隧道断开；不要先重启数据库 |
| OSS 403 | OSS 错误码、requestId、服务时间、签名有效期、RAM 前缀权限 | 过期重新签发；缺权限仅补必要动作；不打印签名 URL 或密钥 |
| 浏览器显示网络错误 | DevTools 的 OPTIONS/PUT 与 CORS 响应、服务端是否收到请求 | 核对页面真实 origin、ETag 暴露和请求头，不能只看 SDK 成功 |
| 完成超时但对象存在 | 任务 FINALIZING、固定对象键、长度与校验、业务引用 | 先核对远端结果，再幂等登记；避免另建任务重复扣费 |
| 删除后对象仍在 | Outbox/清理任务状态、重试记录、引用计数、MQ 投递与消费 | 排除有效引用，恢复投递或重试，最后查对象与容量 |
| 上传返回 429 | 初始化限流入口与请求速率 | 等待后用原幂等键重试，不将限流误记为业务创建成功 |
| 空间无权限 | 当前会话、workspace_id、成员 active/generation | 刷新发生账号切换的标签页，核对操作人和资源归属 |

日志保留任务 ID、对象操作结果和 requestId，定位时联查任务、物理文件、引用与容量。Nginx 日志只记录路径，不记录包含认证信息的查询串。不要把令牌、签名 URL、文件内容和私有配置粘贴到公开 Issue。

Actuator 健康入口仅在服务器内部使用。当前性能指标由版本化压测脚本采集；尚未部署完整时序监控、告警或多实例观测平台。

## 容量与演示维护

默认公开演示配置限制单文件和新空间容量；这不是整个站点的总成本上限。运营者仍需检查账号/空间数量、磁盘、OSS 用量和账目，按明确规则清理自己的测试资料。不要自动删除其他用户的内容来释放空间。

`benchmark.py cleanup` 仅清理它自己的 fixture 关联目录与文件并核对 used/reserved 归零，随后删除本地含令牌的 fixture。它不删除账号和空间；空测试空间可以保留。

Azure 同时绑定网卡和子网 NSG 时，两者都须允许入口流量，核对的是有效规则。[官方排障步骤](https://learn.microsoft.com/en-us/azure/virtual-network/diagnose-network-traffic-filter-problem)。
