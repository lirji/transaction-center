# 最新进展：R3 本地机制完成（2026-09-09）

用户在明确 R3 本地范围后回复“继续”。营销 `feat/referral-completion` 已提交 `ba93fd8`：持久化激活/熔断单调序号、审计、历史版本回滚、本地新鲜检查及真实签名/并发/独立 JVM 恢复测试。

- 本轮 `verify`：SPI 93、referral 服务 226；架构 JUnit 2 项，全部通过。
- V13 只在隔离 MySQL 迁移；生产包无测试探针。未推送、合 main 或部署。
- 详见[本地 R3 交付报告](../../../../marketing-lowcode-platform/docs/delivery/referral-release-runtime/R3_DELIVERY_REPORT.md)。
- R3 本地机制已完成；真实 READY/ACK、权威活动/身份映射、熔断健康协议、参与事务 epoch 栅栏与 control 开放仍待接线。LocalGuard 不是参与许可，默认发布/参与拒绝保留。

以下是 R1/R2 的历史进展，最新 R3 结论以本节为准。

---

# 裂变发布实施状态

- 日期：2026-09-09
- 授权：用户审查方案后回复“继续”，已批准连续执行。
- 状态：blocked（外部协议依赖），已实现切片通过，整体发布未完成。
- 方案：[DELIVERY_PLAN.md](DELIVERY_PLAN.md)
- 交付：[营销发布交付报告](../../../../marketing-lowcode-platform/docs/delivery/referral-release-runtime/DELIVERY_REPORT.md)

## 已完成

交易既有代码五批提交及方案提交保持不变。本轮在营销 feat/referral-completion 新增两个本地提交：

- 8de8460：R1 可信制品验签与严格规则解析。
- abb07c0：R2 VERIFIED 耐久安装、V12、并发/回滚测试和交付文档。

SPI 72、referral 服务 206、架构 JUnit 2 项通过，无失败/错误/跳过。恢复验证为服务对象重建加数据库重读，不是多进程故障验收。已有 CI reactor 能发现新测试，远程 Actions 未运行。

## 未完成与依赖

R2 READY/ACK、R3 激活/熔断/参与许可、R4 control 接线未完成。冻结 SKU 目前是 opaque 引用，其权威映射尚未签收；不能把已有当前 ACTIVE 查询当成冻结版本闭包。可信活动映射与熔断水位协议亦未接线。

已向用户请求权威来源/映射，落实后沿原方案继续，无需再问方案审批。默认发布/参与拒绝保留。

## Git 与环境

未推送、未合并 main、未部署、未迁移共享库。营销其他已有未提交后端/Cursor 改动保留；导航文档与原有修改共存，未整体混入本轮提交。
