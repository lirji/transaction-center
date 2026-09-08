# 支付风控门禁实现与外部合同

状态：内部实现通过最终150项回归，共享V012迁移及validate成功。沿用原FINAL_PLAN第6.2节，不替代真实风控平台联调、供应商认证或生产准入；本阶段只改交易中心，现有平台改造仍排在交易内部完成之后。

## 已核对的缺口

改造前`PaymentDispatchService.preparePlan`在首次`markSubmitted`后返回CREATE，没有风控准入。仅在渠道Service前临时增加一个HTTP调用不够：会把“从未允许渠道创建”的尝试误记为已提交，而且同号补建、旧工作者、取消竞争都可能绕过门禁。

只读核对现有risk-platform：`RiskController`暴露POST `/api/v1/risk/evaluations`；`RiskRequest`要求sourceId、txnId、channel、bizType、accountNo、正金额、币种、eventTime，channel只接受MOBILE/WEB/ATM/API/BRANCH，bizType包含PAYMENT。它是银行交易合同，不直接提供会员/租户语义；不能把支付渠道代码当channel枚举，也不能把裸会员ID冒充真实账户。`RiskResponse`含decisionId、txnId、action及规则/模型版本，当前入口未在响应里回显租户绑定。以上仅是源代码核对，没有发起网络请求、修改平台或证明部署合同已满足。

## 交易中心内部方案

1. 新增`PaymentRiskPort`与不可变请求/结果模型，固定策略注册，默认未配置返回WAIT而非ALLOW。风控HTTP适配器在外部字段、身份认证与决策语义确认后接入，不能接受调用方指定URL或任意action字符串。
2. 新增支付模块所属`PaymentRiskMapper`及XML，保存稳定评估号、tenant/attempt/order绑定、不可变输入摘要、决策号、允许/拒绝/待确认状态、规则/模型版本与有效期、结果版本。迁移从当前共享最新版本之后递增；每张新增表和每个字段中文COMMENT。避免把IP、设备或账号原文写入明文审计/日志，字段来源与最小化策略须逐项定义。
3. 恢复Worker先在短事务中准备评估快照及fencing，再事务外调用风险Port；结果持久化重检绑定、版本、有效期与可信来源。重复评估/回执不覆盖已消费事实；超时、缺适配器、未知动作、绑定不符均不允许CREATE。输入变化需要新版本，不复用旧ALLOW。
4. 首次CREATE许可事务持订单→意图→尝试锁，重检取消/过期、有效ALLOW与尝试状态，随后才markSubmitted；许可与租约提交后再调用渠道。风控返回ALLOW期间已取消、已收款或被接管时，不得再创建支付。
5. 认证后的同请求号CREATE补建也必须过相同风控许可路径；风险过期不得跳过。QUERY/CLOSE用于查明已有资金事实，不因风控不可用而阻断；可信回调、真实收款入账、原路退款不能因为会员冻结或新风险拒绝而丢弃。
6. DENY不是“渠道未发生交易”的证明。只有从未发出CREATE许可且无其他在途尝试时，才可使用已有本地未派发关闭协议；已提交/UNKNOWN保留资金与库存状态，按同号查单/可靠关单恢复。REVIEW保留人工流程，不自动当失败释放。

## 预计变更范围

- payment：风险Port、策略、模型、持久化服务与Mapper/XML；PaymentDispatchService的首次/补建许可门禁。
- jobs：PaymentRecoveryWorker接入评估阶段，继续使用现有PAYMENT_RECOVER任务与fencing；网络不持事务，不能另造无租约旁路。
- server：默认fail-closed配置、增量迁移与集成/合同测试。不增加允许前端伪造风险结果的接口。
- 文档：支付协议、风险字段映射、状态迁移、外部合同、覆盖账本和运行手册同步。

## 必须验证

- 默认缺能力、超时、拒绝、未知结果均零CREATE；正金额门禁与零元订单流程分离。
- ALLOW仅绑定正确租户/尝试/冻结金额/币种/版本；过期、重放、跨租户及篡改拒绝。
- 风险调用期间取消/到期/付款/租约接管，旧结果不得产生CREATE或资金副作用；事务回滚不会消耗许可。
- 同号补建不能绕过门禁；QUERY/CLOSE与已验签真实资金处理不被风险阻塞。
- 断言风险与渠道网络阶段无活跃事务或绑定数据库连接；隔离MySQL故障测试，不在共享库注入故障。
- 全量回归、独立高风险审查、中文注释与Mapper门禁通过后才执行授权增量迁移。

队列年龄、采集告警、真实网络超时/配额、KMS、退款/履约、正式消息、CI与全量三高/容灾仍是原需求，不因本切片而删除；交易中心完成后继续完整裂变改造。

## 当前具体合同

`PaymentRiskPort.prepareInTransaction`准备本地评估；`evaluate`只在事务外调用最多一个固定`PaymentRiskProvider`；`acceptInTransaction`检查回显归属并持久保存结果。没有Provider、空结果或Provider运行时异常都返回WAIT，不记录供应商异常对象，不提供关闭门禁或默认ALLOW开关。3秒是传给适配器的总预算合同，当前尚无实际HTTP客户端落实超时，不能称网络限时已认证。

评估输入含租户、尝试/请求号、订单/会员主键、支付渠道与渠道商户、金额/币种及PAYMENT_RISK_V1规范摘要；不假造IP、设备或银行账号。结果必须回显评估号、租户、请求号、摘要，ALLOW/DENY需非空decisionId、ruleVersion、modelVersion，纯规则模型用显式NONE。结果和输入的默认toString脱敏。

| 持久状态 | 行为 | 重试/替换 |
|---|---|---|
| PENDING | 不放行；WAIT不改结果 | 5分钟接收窗口内同评估号，窗口过期追加新评估 |
| ALLOW | 仅有效期内且订单/租约条件满足才发CREATE许可 | 有效期最多接收时起60秒且不超过本轮deadline；过期追加新评估 |
| DENY | 创建门禁返回REVIEW，不能当成资金失败释放资源 | 不覆盖或自动重启；人工流程仍待补齐 |

V012新增`tc_payment_risk`历史表和`tc_payment_attempt.risk_evaluation_no`当前指针；所有表/字段中文注释。当前指针由同租户同尝试复合外键保护，持订单锁后追加评估并切换指针同事务提交，不对缺失风险记录执行范围锁查询。四条SQL均在PaymentRiskMapper XML；精确指针降低该路径间隙锁风险，不保证系统绝无死锁。

首次CREATE和同号补建先核对ALLOW，再写submitted标记。Worker等待fencing锁后调用`PaymentRecoveryPort.assertCreateAllowedInTransaction`重新检查风险/订单期限，失效抛错使提交标记与本轮副作用回滚。风险网络期间被接管时，风险结果与最终fencing失败一同回滚；它不同于已经收取的真实资金，不能类推丢弃渠道收款证据。

收到ALLOW/DENY后100毫秒持久重检，重新走完整许可路径；WAIT保留原5秒起至5分钟封顶抖动退避。不能给有效ALLOW套用已累积的长退避，否则会形成“批准→调度时已过期”的循环。100毫秒只是任务到期时间，不保证负载下执行延迟；调度积压使ALLOW过期时重新评估，禁止强行延长旧许可。

有效期在创建许可事务末尾核对，不承诺任意进程暂停后网络仍发生在原有效期内；渠道调用仍须稳定请求号、可靠关闭屏障及真实供应商合同兜底。业务时钟沿用注入Clock，跨节点时钟同步/生产偏移演练未完成。滚动升级须先停旧Worker并确认旧版本不再发CREATE，不能让不含风险门禁的旧代码继续创建；DDL兼容不等于安全支持旧写者混跑。回退不得恢复绕过风险的CREATE路径。

HTTP仍39操作/30路径，无用户可伪造风险结果的新接口。前序支付测试通过测试辅助方法显式写入可信ALLOW前置事实；新增risk测试直接调用真实payments.create保留默认WAIT，不能以测试夹具冒充真实风险认证。

## 实际验证与残余项

最终`mvn -q -l build-payment-risk-final.log verify`退出0：Foundation119＋WorkerRuntime10＋PaymentRiskProviderContract4＋其他17，共150项，失败/错误/跳过均0。原始10份报告及共享迁移输出保存在`.codex/tasks/referral-end-to-end/evidence/payment-risk/`。

新增10项数据库场景覆盖默认无Provider、过期补建与QUERY/真实资金不阻断、结果不可覆盖/错租户/旧轮拒绝、有效期上限/过期/SQL3819、旧租约结果回滚/跨尝试指针FK1452、高attempts决定后短重检并最终CREATE、风险网络期间取消、12并发首次/同尝试幂等、风险网络期间接管、真实任务行锁等待并推进业务Clock至许可过期后CREATE标记回滚。最后一项控制逻辑时钟，不声称实等60秒或已观测数据库全部锁等待队列。4项Provider合同验证缺配置/异常/空结果不放行、事务内拒绝网络、重复策略及脱敏。

独立审查两项确认问题已修复：ALLOW最多60秒却沿用高次数长退避造成永久错过；ALLOW分支SQL CHECK遗漏显式非空。指针路径另消除原空范围锁查询，并发回归通过。首轮定向3个测试夹具错误修正后18项定向通过，再全量150项通过，失败历史保留；没有放宽业务约束。

共享V012执行及validate成功，001～012全成功，30表/394字段中文注释缺失0，风险/支付尝试/任务0行；无共享业务测试数据或应用部署。V001～V012冻结，后续V013。当前18Mapper/130SQL，8模块。Flyway对MySQL版本验证范围警告仍保留。

仍缺真实供应商字段/身份/决策时效合同与HTTP认证、实际网络超时/配额/告警/人工REVIEW/KMS/时钟偏移和三高验收。内部数据绑定不是供应商签名验证；本地批准测试不能开启真实收款。后续按REFUND_IMPLEMENTATION_PLAN.md推进退款，同时保留全部前序运维缺口。
