# T12与裂变合同交付报告

## 结果与范围

T12现有同库机制的多进程正确性验证已完成；裂变身份/证据/发布合同已完成源码对照与可审查基线，外部权威及业务决策尚未签收。未修改生产Java逻辑、迁移或前端页面，未提交/推送；交易、营销既有未提交改动保留。

| AC | 实现与结果 |
| --- | --- |
| 01 | 专属MySQL、2 API/2 Worker角色JVM；独立PID/端口/池，故障后新PID恢复 |
| 02 | 两API同键订单/库存写只生效一次，异内容409、跨租户404、匿名401；API重启按原键恢复 |
| 03 | 双Worker同时释放屏障竞争同任务，一个持有者，一个EMPTY |
| 04 | SIGSTOP、自然数据库时钟过期、新进程token递增接管、SIGCONT；旧库存/流水和任务更新整体回滚 |
| 05 | 专属Worker强制退出、原任务接管、重启无重复完成 |
| 06 | 生产PaymentRecoveryWorker返回未决RETRY/已付DONE，实收保持2条，库存守恒 |
| 07 | C01/C02/C03/发布责任、字段/路径映射、版本差异、禁止转换、验收矩阵及11份源码指纹已整理；未开放真实流量 |

## 验证

`TESTCONTAINERS_RYUK_DISABLED=true bash scripts/verify-multi-instance.sh`通过。实际执行1项多进程集成编排（含7组验证场景）及10项WorkerRuntime回归，无失败/错误/跳过；Maven verify打包成功。不能将这11项与此前454项累加当作新一轮全量回归。

生产jar检查不含multiprocess驱动及业务种子。GitHub Actions已新增multi-instance专项，执行同一脚本、保留诊断和Surefire报告7天；YAML检查通过，远程未触发。

结果、PID、HTTP小样本耗时、源指纹见[verification.json](verification.json)。运行环境、复现和清理见[运行说明](../../MULTI_INSTANCE_VERIFICATION.md)。审查/QA见[审查报告](REVIEW_REPORT.md)、[QA报告](QA_REPORT.md)。本轮范围pass。

## 合同落实与剩余条件

[接入基线](../../contracts/referral-integration/CONTRACT_BASELINE.md)和[机器状态](../../contracts/referral-integration/contract-status.json)明确：

- 交易批量证据与首单查询是两个不同接口；营销旧目标不能覆盖既有DTO。
- canonicalSubject不能从memberId或机器JWT sub猜测，组织/门店仍需权威来源。
- paid不等于settled，首次确认已付不等于任意营销首单，待退款不能被忽略；不同来源revision不合成伪版本。
- 发布需制品闭包、签名/ACK、激活epoch及新鲜kill-switch；现有拒绝不可直接移除。

已经向用户询问权威主体/组织店铺来源、maxBindAge起点；未收到答复前保持待决。BFF、KMS/JWKS、实际政策含义与外部签收也不能用本轮文档当作已上线。营销仓库只同步实施导航及恢复记录，未更改业务代码。

## 运行与后续

现有4180/8400演示服务健康，未重建或操作其数据。所有故障仅针对本轮创建的进程和隔离MySQL。测试进程按批次调用生产Worker，八类常驻调度及真实渠道/Kafka消费者部署仍为T10/T05；P2/P3拆库协议和18类完整矩阵、容量/多AZ不在本次通过声明内。

下一步根据权威来源和绑定规则回复落地身份/证据适配；发布闭包/ACK可以依既有合同细化实现，但不能开放未签收的参与/发奖链。无数据库迁移，撤回本轮测试/CI不会影响业务数据。
