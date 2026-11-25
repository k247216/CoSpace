# 性能记录

## 环境与口径

2026-11-25，Azure Linux VM，2 vCPU / 约 8 GiB 内存；Java 21，应用容器上限 768 MiB，JVM -Xms96m -Xmx320m；MySQL 8.4、Redis 7.4、RocketMQ 5.3.2。客户端 Python 标准库线程池与服务端同机，经回环 Nginx /api 请求，HTTP keep-alive、闭环负载。

工作负载为同一个团队目录：199 个文件夹与 1 个 1066 字节文件，共 200 条；每次校验业务状态及返回数量。每档预热 5 请求，并发 1/4/8/16，各 300 请求；前后各三轮，共 7200 次测量请求。P95 使用 nearest-rank，表格取三轮各项统计量的中位数，不是混合样本的全局 P95。

基线来自发布前仍对读取使用空间排他锁的服务；修改版为移除列表、搜索和下载鉴权不必要排他锁的 a7ae60a8a14a412e。权限写操作保留原有协调。

## 结果

| 并发 | 原版 RPS | 修改版 RPS | 原版 P95 ms | 修改版 P95 ms | 业务错误 |
| --- | --- | --- | --- | --- | --- |
| 1 | 93.29 | 83.49 | 15.71 | 20.85 | 0 |
| 4 | 124.63 | 124.39 | 46.66 | 48.80 | 0 |
| 8 | 140.75 | 155.89 | 74.50 | 75.73 | 0 |
| 16 | 117.00 | 170.15 | 193.82 | 132.19 | 0 |

16 并发的 RPS 中位数增加约 45%，P95 降低约 32%；1/4 并发未稳定改善，8 并发的 P95 也未改善。原始各轮有明显预热与运行时波动，且前后未做交错随机实验，不能把全部差异归因于加锁修改。更直接的正确性证据是：真实 MySQL 事务持有空间、目录和文件排他锁时，读请求能在规定时间内返回；移除角色后下一次访问失败。

这是小数据集目录接口回归，不是最大承载、独立压测机结果、公网 RTT 或大文件上传带宽。没有长期稳定性与资源饱和曲线，不写“支持上万并发”或“生产吞吐提升”。

## 重复请求与限流

[初始化与完成验证](../benchmark/results/idempotency-after.json)：已完成任务初始化重放 16 次，初次 10 次 HTTP200、6 次 HTTP429；429 等待后重试复用原任务。完成重放 16 次，最终只有一条逻辑文件，used=1066、reserved=0。它证明已完成任务的重放幂等，真正 FINALIZING 竞争与回滚窗口由后端事务测试覆盖。

## 复现

```bash
python3 scripts/benchmark.py prepare --fixture runtime/benchmark-fixture.json
python3 scripts/benchmark.py measure --fixture runtime/benchmark-fixture.json --output runtime/measurement.json
python3 scripts/benchmark.py verify --fixture runtime/benchmark-fixture.json
python3 scripts/benchmark.py cleanup --fixture runtime/benchmark-fixture.json
```

默认 API 为 http://127.0.0.1:18080/api，可用 --base 修改；只对有权限的独立测试部署运行。fixture 含测试会话，只写入 runtime 且权限为 0600，不进入仓库。脚本限制最大并发 32、每档最多 5000 请求，业务错误不会被 HTTP200 掩盖。

[原始聚合 JSON](../benchmark/results/) 包含每轮 RPS、P50/P95/P99、请求数、响应字节、错误数和时间。配置及用户令牌不在这些结果中。
