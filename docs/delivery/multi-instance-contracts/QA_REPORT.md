# QA报告

## 环境与边界

2026-09-09，macOS/Java21、Docker Desktop、Testcontainers MySQL8.4.11；独立transaction_center_multi_it，V001–V033。编排Spring上下文另起两个API JVM和两个Worker角色JVM，故障后新增重启实例；每个子JVM最大堆256MiB，默认连接池10。API绑定回环随机端口，测试JWT使用真实RSA签名校验。未操作共享演示数据。

Worker在本轮按管道命令执行生产JobLeaseService及PaymentRecoveryWorker，不开启WorkerRuntime循环。租约探针使用RECON_QUERY类别且只在隔离库；库存调整是验证“业务副作用+fence”事务的显式测试动作，不是生产对账行为。

| AC | 用例 | 已观察结果 |
| --- | --- | --- |
| 01 | 2 API/2 Worker独立JVM | 不同PID/随机端口/独立连接池，记录ready.json与最终instances |
| 02 | 两API同键下单及库存调整、异内容冲突 | 同内容唯一订单/一次库存调整；异内容409；跨租户404、未认证401 |
| 03 | 双Worker同屏障竞争 | 一个CLAIMED、另一个EMPTY，持有者正常完成 |
| 04 | 持有者暂停、自然过期、接管、旧进程恢复 | 同jobId、token从1递增2，旧业务写入返回JOB_LEASE_LOST并整体回滚 |
| 05 | 持有者强制退出、接管、进程重启 | 原任务由另一进程完成，新PID重启后无重复完成；API重启后原幂等键仍恢复原订单 |
| 06 | 真实PaymentRecoveryWorker批次 | 未决支付RETRY、已付支付DONE，正常/异常实收仍两条，不生成假渠道成功 |
| 07 | 三类合同核对 | 11个代码来源指纹、实际DTO/路径/默认拒绝与发布时间上限对照，机器状态为未启用 |

本轮只声明低并发正确性。HTTP P50/P95/P99仅基于本轮少量写请求，用于诊断；没有目标负载、生产SLO或容量达标结论。常驻公平调度由既有WorkerRuntimeTest回归，不能替代真实部署T10。

合同外部验收尚未执行：canonicalSubject/组织店铺权威来源、maxBindAge起点、BFF/JWKS/KMS、C02/C03政策含义与发布签收仍待落实。默认拒绝保持原样。

最终命令、数量与PID证据见DELIVERY_REPORT.md及verification.json。无前端业务变更，不重跑无关浏览器测试。
