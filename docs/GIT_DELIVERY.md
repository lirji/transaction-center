# 交易中心 Git 交付记录

日期：2026-09-08。用户授权范围仅 `transaction-center`，远程 `git@github.com:lirji/transaction-center.git`。关联营销平台和权益中心不在本次提交或推送范围。

## 分支与合并顺序

初始本地 main 无提交，远程查询也无引用。先建立 Maven 聚合工程根提交，再按以下能力建立分支，以 `--no-ff` 合并到 main，保留分支与合并节点；没有覆盖旧历史或使用强制推送。

| 顺序 | 分支 | 能力及依赖 |
|---|---|---|
| 1 | `feat/member-catalog-inventory` | core 可信身份/持久基础、会员、商品、库存 |
| 2 | `feat/order-payment-refund` | 依赖 1；订单/归因、支付、退款、永久业务回放 |
| 3 | `feat/runtime-attribution-api` | 依赖 2；任务恢复、HTTP/鉴权、实际归因接线、V001–V032、集成测试和 OpenAPI/事件样例 |
| 4 | `feat/error-sdk-contracts` | 依赖 3 的错误/事件定义；独立错误 SDK、AsyncAPI、机器合同检查 |
| 5 | `chore/deployment-ci` | 依赖 4；开发 Compose、监控规则、验证脚本和 GitHub Actions |
| 6 | `docs/delivery-handoff` | 依赖上述能力；设计/操作/验收边界、交接文档与本次可携带验证摘要 |

各代码分支按依赖增加 Maven modules，不产生引用未提交目录的中间 reactor；最终 pom.xml 与提交前完整版本字节一致。能力之间有依赖，因此后续分支基于前一阶段已合入的 main，不假装彼此独立。生产代码、测试和迁移保留原始字节，仅调整提交组织并补正文档可用性说明。已有源码末尾空行保留，不为初次导入改变历史已验证源码。

## 本次实际验证

- 五个代码/配置能力提交分别通过独立 `git archive` 快照的 `mvn --batch-mode -o validate`。validate 只验证 reactor/项目模型，不等于业务测试。
- 完整代码快照执行 Maven package，所有模块构建成功；定向运行 20 项 Java 测试：错误 SDK 4、权威映射 11、架构/中文注释 3、Mapper 合同 1、OpenAPI 1。失败/错误/跳过均为 0。
- `bash scripts/verify-contracts.sh` 成功：80 个错误码（79 个生产发出）、7 类 V1 事件、12 个正例、9 项 Python 测试。
- 未重复运行真实数据库全量，未执行共享迁移、部署或真实渠道操作。历史归因 428 项/34 套及之后断言/映射专项保留各自范围，不能相加称当前全量。
- 本次提交前静态扫描未发现真实私钥/常见访问令牌；本地环境配置、构建产物和 Agent 检查点按 `.gitignore` 保留在本机。

可携带记录：[验证摘要](delivery/git-publish-20260908/verification.json)、[代码快照 SHA-256](delivery/git-publish-20260908/source-sha256.json)、[前五能力提交记录](delivery/git-publish-20260908/capability-commits.json)。文档分支与最终 main 的提交号通过 Git 历史查阅，避免文档记录自身哈希的循环引用。

定向构建命令：

```sh
mvn --batch-mode -o -Dtest=RetryDecisionTest,AuthoritativeReferralMappingAdapterTest,QualityGateTest,MapperContractTest,OpenApiTest -Dsurefire.failIfNoSpecifiedTests=false package
bash scripts/verify-contracts.sh
```

离线 Maven 命令要求本机已有依赖缓存；新环境先按正常 Maven 依赖下载流程准备。完整本地/CI 验证仍使用 `bash scripts/verify.sh`，需要 Docker；不得因环境缺失静默跳过数据库测试。

## 远程核验与边界

发布时推送上述六个分支及 main，不删除分支。是否推送完成以实际命令结果及 `git ls-remote origin` 与本地 SHA 对照为准；本文件自身不冒充远程确认。main 推送会触发仓库 GitHub Actions，其远程运行结果须单独查看，不能把本机专项通过称为远程 CI 通过。

历史交接中的 `.codex/`、CODEX_PROGRESS.md、临时测试快照和本机日志不随克隆分发；其引用用于原开发机追溯，不是远程可下载的原始证据。本次共享源码指纹和验证摘要已单独提交；需要重验时运行真实脚本，不编造历史报告。

完整裂变、隐私决定、真实权威来源、支付/退款等渠道、生产参数、监控/容量/容灾、外部消费者签收仍有待完成事项。将代码合入 main 不等于生产验收或这些门禁已满足。
