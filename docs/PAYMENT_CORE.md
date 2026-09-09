# 支付核心：受理、收款与可靠关闭

> 时效说明（2026-09-08）：本文保留 V007 支付核心设计与当时验收范围。后续已增加耐久 Inbox、恢复 Worker、异常补偿和退款资金流水；正文早期“待实现”不能作为当前能力清单。参见 [业务说明](BUSINESS_GUIDE.md)、[异常补偿](REFUND_COMPENSATION.md) 和 [退款恢复](REFUND_RECOVERY_WORKER.md)。真实渠道接入仍需外部验收。

## 当前交付边界

新增 transaction-payment，复用同一MySQL本地事务和Mapper＋XML。已实现渠道预登记、支付意图／尝试、内部调用计划、可信观察结果处理、收款与运营资金流水、订单／库存／会员首单原子确认、可靠关闭后的库存释放。

[任务租约](JOB_LEASES.md)已补充同事务入队、到期接管、令牌保护及升级收尾补漏；[可信回调密文接收与本地处理Worker](PAYMENT_CALLBACK_INBOX.md)实现接收／入队、加密及异步原子入账；[支付恢复编排](PAYMENT_RECOVERY_WORKER.md)实现事务外策略调用、未知查询及同号安全补建，后续已补订单唤醒与默认关闭常驻调度。[风险门禁](PAYMENT_RISK_GATE_PLAN.md)默认WAIT，仅有效ALLOW允许首次/补建CREATE。仍无真实渠道和风控供应商接通、原始报文验签入口、自动补偿退款或生产放量配置。测试传入的是内部可信观察，不能称真实支付接通；原TC-10～32及容量/容灾/前端验收继续保留。

## HTTP API

| 方法／路径（基址 /api/v1/trade） | 权限与输入 | 输出及含义 |
|---|---|---|
| POST /admin/payment-channels | trade.admin；channelCode、channelMerchantNo、currency、credentialRef、returnUrlId、returnUrl；Idempotency-Key | 200 resourceId，追加不可变配置，不发起网络 |
| GET /admin/payment-channels | trade.admin；after／limit | 渠道摘要列表，不返回credentialRef或回跳URL |
| POST /orders/{orderNo}/payments | 本人；channelCode、returnUrlId；Idempotency-Key | 202 PaymentView，仅持久受理 |
| GET /payments/{paymentNo} | 本人，冻结后仍可查历史 | 200 PaymentView，单语句一致快照，不发起查单网络 |

PaymentView：paymentNo、orderId、status、expectedAmount、currency、activeAttemptNo、attemptStatus、version。status为意图的READY／PENDING／SUCCEEDED／CLOSED／REVIEW；version属于意图，不作为整个资金证据或任务租约版本。attemptStatus允许为空。当前不返回paymentAction，前端不能构造支付跳转或“确认成功”按钮。

金额、币种、商户均读取订单；请求不能传amount、tenant、member、success或任意URL。客户端只选择预登记回跳别名；配置URL必须HTTPS、无用户凭据和片段。页面回跳不构成成功证据。

同HTTP键不同请求体409；换HTTP键仍复用同订单支付意图和相同未决尝试。旧渠道未可靠关闭时切换返回409 PAYMENT_UNRESOLVED；相同渠道重试不会更换请求号。可靠关闭旧尝试后，若订单仍可支付，允许同意图建立新渠道尝试。重放旧HTTP命令只返回该支付意图的当前视图，不创建新尝试。

## 内部端口与调用前置

### PaymentDispatchService.prepare

只供后台适配器使用，没有HTTP入口，也不是任务执行器。拒绝调用者携带外层数据库事务，自建10秒短事务，返回前本地许可已提交。

- 首次：有效风险ALLOW才将PREPARED→UNKNOWN并设置submitted_at，返回CREATE及冻结渠道请求参数；PENDING返回RISK且不写提交标记，DENY返回REVIEW。查询/关单/真实收款处理不受新风险拒绝阻断。
- 再次：只返回QUERY，沿用同一个merchant_request_no。首次许可后进程在真正发请求前崩溃，也先查原号，不能擅自换号；无记录与渠道未传播的区别留给渠道合同。
- 过期或CLOSING：设置不可清除的本地close fence，返回CLOSE，不释放库存。
- 已有成功异常资金：返回REVIEW，不回退失败。
- 可靠关闭或正常支付完成：DONE。若最后尝试已可靠关闭、active为空后用户才取消，可使用保存的可靠证明直接完成关闭。

PaymentRecoveryWorker通过MANDATORY公开Port将准备/fencing/等待后的许可终检置于同一事务，网络在事务外；风险结果持久化、查询退避、认证能力下同号补建及默认关闭常驻调度已实现。仍须接通真实风险供应商、限流、UNKNOWN年龄监控及生产容量。attemptVersion不是租约令牌；进程暂停后的网络重放仍依赖渠道同号幂等和可靠不可支付屏障。

### PaymentObservationPort

只有通过渠道商户绑定、原始报文验签或认证查单的适配器才能调用；当前没有公开回调入口，/callbacks仍拒绝访问。

- received：tenant、requestNo、真实交易号、金额币种、providerPaidAt、evidenceRef。每笔真实资金必须耐久记录；同真实交易号重复返回原事实，金额／币种／尝试或渠道时间冲突返回PAYMENT_FACT_CONFLICT，不覆盖原证据。
- closed：tenant、requestNo、evidenceRef。调用者必须证明该商户请求号不可能再成功支付；普通HTTP200、查询不存在、失败提示、UNKNOWN、本地TTL或用户回跳均不能调用此方法。

后续回调接收层必须先把必要原始密文及验签信息耐久保存才成功应答，再异步调用上述处理器；若业务事务失败，依赖原始记录重试。当前已新增内部密文接收与租约处理链路，但未接通真实渠道HTTP应答，不将内部提交后异常模拟当作真实回调断响应验收。可信证据引用不是客户端可自行声称已验真的标记。

## 事务和锁顺序

受理：可信会员准入→订单锁及许可→商户／渠道共享锁→意图锁→尝试写入／活跃指针→内部Outbox，同事务。

处理：读取尝试的不可变订单引用→订单锁→意图锁→尝试当前读→真实交易号永久去重→收款＋运营流水→正常订单副作用或异常事件→提交。

真实交易号去重采用先插入、后锁定读取：`INSERT ... ON DUPLICATE KEY UPDATE id=tc_payment_receipt.id` 命中任意唯一键时不覆盖原资金字段；再按渠道及真实交易号当前读，以新生成的receipt_no识别本次插入或历史重放，不依赖驱动的影响行数配置。读不到对应真实交易号或历史尝试／金额／币种／渠道时间冲突时，整笔事务回滚。

回归曾复现两个不同订单先锁定不存在交易号后插入造成的MySQL间隙锁死锁，上述顺序已修复该路径。它不证明系统不存在其他死锁；后续耐久接收和任务层仍须提供有界整事务重试，不能只重试事务中的最后一条SQL。

正常副作用：库存预占转已售→MemberTradeFactPort唯一首单／累计→订单PAID／financialStatus=PAID／paidAt→内部Outbox。paidAt与本次本地消费确认时间一致，completedAt仍为空；商业完成需履约流程。任何一步或调用方外层事务失败，收款、流水、库存、订单、首单及事件全部回滚。

冻结不阻断已发生收款。零元订单在申请支付阶段拒绝，不创建渠道尝试，也不伪造收款／首单。

## 所有真实资金的分类

| disposition | 裁决 | 订单和库存 |
|---|---|---|
| NORMAL | 首笔有效足额同币种且订单未正常支付 | 原子确认一次 |
| EXCESS | 正常收款已绑定后另一个真实交易号等 | 保留第二笔收款和流水，不重复履约 |
| AMOUNT_MISMATCH | 实收金额或币种与冻结应付不符 | 入账进入REVIEW，不正常履约 |
| AFTER_TERMINAL | 已可靠关闭尝试却出现矛盾成功 | 保留关闭证明及资金，不恢复旧尝试 |
| AFTER_CLOSE | 已取消订单收到其他矛盾资金 | 入账、不复活订单、不重新占库存 |

异常均追加内部异常事件；自动补偿退款／人工核对／报警消费者未实现，不能说已退款。CLOSED只描述该正常支付意图的关闭，并不抹掉矛盾迟到资金。后续运营查询须展示异常资金。

同订单关闭与收款锁定同一行：收款先成功则关闭不能回退；关闭先可靠完成则迟到资金分类异常，不复活订单。新尝试只能在旧活跃指针可靠清除后创建；旧证明重放不得清除新活跃指针。

## V007 数据变更

新增5表，表／字段全部中文注释：

- tc_merchant_channel：租户逻辑商户、渠道收款主体、币种、托管引用及回跳地址登记；全局渠道收款主体唯一，避免回调路由歧义。
- tc_payment_intent：一订单一意图；冻结金额币种、唯一活跃尝试和正常收款指针、不可清除关闭屏障。
- tc_payment_attempt：稳定且终身不可复用的请求号、原配置引用、提交许可和可靠关闭证明。
- tc_payment_receipt：每个渠道商户真实交易号唯一；一订单NORMAL槽位唯一；异常不限制笔数；退款成功＋在途预占≤实收。
- tc_money_ledger：每笔收款的运营流水，business_key=receipt:<id>永久去重；不是财务总账。

指针复合FK限定同租户、同意图；收款关联限定同一尝试／意图／订单。所有SQL在payment Mapper XML，订单和会员表只能经对应Port操作。V001～V006不变。

首期配置限定一个渠道收款主体绑定一个租户和币种；币种扩展、历史配置版本／密钥轮换需新增版本路由设计，不直接覆盖配置或重用旧请求号。运营账本当前仅RECEIPT，退款阶段须增量扩展退款关联和类型约束，不能直接把状态字符串改成支持退款。

## 验收与未完成项

隔离MySQL测试覆盖：并发受理／重复通知、正常与多付、错金额币种、外层失败整体回滚、冻结收款、两订单并发及迟到早渠道时间、可靠关闭前不释放、关闭后矛盾资金、旧证明不影响新尝试、关闭／切换／取消三方竞争、跨订单复用真实交易号拒绝、HTTP身份权限与金额篡改。

独立上下文审查已进行；准确运行结果见[验证记录](VERIFICATION.md)和[覆盖账本](ACCEPTANCE_COVERAGE.md)。测试规模不能替代原1000并发、三高容量、真实渠道交错或完整裂变验收。

接下来：原始验签HTTP入口、真实渠道实现／授权沙箱、风控、订单取消／过期唤醒与常驻调度、异常补偿及部分退款、履约／完成／退货、正式消息及证据查询、生产运维和跨系统裂变。密文接收及内部恢复编排已实现，见相应合同与最新验证记录。
