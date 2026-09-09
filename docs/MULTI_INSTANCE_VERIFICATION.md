# T12隔离多进程正确性验证

本轮验证现有同库架构的跨进程幂等与fencing恢复。两个API JVM和两个Worker角色JVM共享专属Testcontainers MySQL；与4180/8400和演示库完全分离。Worker使用受控批次执行生产租约/恢复代码，常驻部署、Kafka和外部渠道验收仍为T10。

```bash
bash scripts/verify-multi-instance.sh
```

要求Docker、Java21/Maven和支持SIGSTOP/SIGCONT的Linux/macOS。默认测试MySQL8.4.11，可设TRADE_TEST_MYSQL_IMAGE；本机若需禁用Ryuk，显式在命令前加TESTCONTAINERS_RYUK_DISABLED=true，并在强制中断后按记录核对专属容器，不清理共享容器。

执行：初始化专属库和业务种子 → 启动2 API/2 Worker → 跨API并发同键写 → 双Worker同步竞争 → 暂停/过期/接管/恢复 → 强制退出/接管/重启 → 生产支付恢复批次 → API重启原键重放 → 数据库守恒断言 → 清理。

测试租约2秒用于故障用例、30秒用于正常完成；生产PaymentRecoveryWorker自身仍按其30秒租约运行。旧Worker测试故意先在本地事务修改库存，再校验任务令牌；必须连同库存及流水一起回滚才通过。不是只检查任务状态，也不是改SQL时间模拟过期。

证据在`transaction-server/target/multi-instance/<run-id>/`：每个实例ready.json包含PID/端口/角色，process.log是诊断日志，cases.json逐项记录，result.json是最终结果和小样本HTTP耗时。每个阶段还采集专属数据库容器CPU/内存/块IO、数据库连接/行锁/读写计数、未完成队列年龄以及子进程CPU时间（平台不支持时为-1）；这些是诊断快照，不是长稳压测。恢复后应为4订单、2实收，测试仓stock_total=108且等于available+reserved+sold；这些数字仅属于本测试数据集。

GitHub Actions的multi-instance任务执行同一命令并上传报告，未自动部署。用例覆盖P0中的D-01/D-13/D-17相关现有机制；D-02–D-12其余场景、D-14–D-16和D-18中需要新跨服务协议的部分继续按各自阶段验收，不因本轮通过而标记全部18类场景完成。容量、真实渠道可靠关单/回调丢失、Outbox消费者和多AZ另行验证。

失败时优先检查对应实例日志；脚本自动终止其创建的进程，父JVM关闭钩子也会终止仍运行的测试子进程。父JVM被SIGKILL时无法执行钩子，需按本次PID及容器ID核对残留，不用全局docker清理或killall。
