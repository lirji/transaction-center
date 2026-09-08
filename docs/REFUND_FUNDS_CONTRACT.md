# 退款资金内部合同

## 范围与依赖

本切片实现支付域拥有的退款资金预占、永久去重和退款账本，是原FINAL_PLAN第6.4节及TC20～24的前置部分，不代表退款功能全部交付。后续V014的正常申请、本人授权、明细分配和内部审批/执行见[退款应用](REFUND_APPLICATION.md)；真实原路调用、退款耐久Inbox与Worker、净支付证据API、退货验收和营销追回仍须完成。资金Port没有直接HTTP入口，不能让客户端提交“退款成功”。

主要代码：`PaymentRefundPort/Service`、`model/RefundSource/RefundHold`、`mapper/PaymentRefundMapper.java`及同名XML。采用端口隔离和事务脚本编排；Mapper负责SQL，Service负责状态与证据约束，不增加无职责转发层。未来`transaction-refund`只调用payment/order公开Port，不能读写他域Mapper或表。

## 数据与事务

V013增加`tc_payment_refund_hold`，记录稳定退款号、来源真实收款、订单、金额币种、原渠道/商户、派发标记和终态证据。每个字段及表均有中文注释。扩展原`tc_money_ledger`的REFUND方向与退款引用，保留原RECEIPT历史不变，并更新旧注释。

- `(tenant_id,refund_no)`永久唯一；不依赖HTTP命令保留期。`reservation_no`用于辨认本次插入及稳定账本去重，不是另一个渠道请求号。
- `(channel_code,channel_merchant_no,provider_refund_no)`唯一，防两个退款消耗同一渠道退款事实；尚未成功时允许NULL。
- 预占的复合外键绑定租户/收款/订单；账本退款引用绑定同租户、同来源收款和退款号。
- 金额是最小币种单位，范围1～9007199254740991；币种三位大写且与真实收款一致。
- 额度条件更新和数据库CHECK保证`refunded_amount + refund_reserved_amount <= amount`。扣预占与加成功金额在单条UPDATE内完成。
- 每个公开方法要求外层本地事务（MANDATORY）。调用方同时提交应用状态、审批结果、账本、Outbox和最后的任务fencing；任一异常都回滚，不得捕获后继续提交部分状态。网络及加解密外部能力不能在此事务内等待。
- 统一锁序：订单→支付意图→真实收款→退款资金hold→后续应用/明细→最后任务fencing。初读仅定位不可变归属，不用初读余额决策；先UPSERT再当前读退款唯一键，避免预锁不存在行的间隙。

## 内部API及状态合同

全部方法在`PaymentRefundPort`。租户来自可信内部身份，不从不可信回调原文直接采信。

| 方法 | 前置条件及效果 |
|---|---|
| `lockNormalSource(tenant,order)` | 订单/意图锁后按正常成功指针定位实收，并复核订单/金额/币种/分类；无正常实收报REFUND_NOT_PAID |
| `lockSource(tenant,receiptNo)` | 获取原真实收款和最新额度；不执行会员本人授权，退款应用必须先做归属检查 |
| `reserve(tenant,refundNo,receiptNo,orderId,amount,currency)` | 新号占款成为HELD；同号同来源/金额/币种返回原记录，任何终态重放均不重新占款；变更绑定报冲突 |
| `markSubmitted(tenant,refundNo)` | HELD→SUBMITTED，记录不可清除的首次派发屏障；重复SUBMITTED/SUCCEEDED不再转换；不是独立渠道许可 |
| `releaseUnsubmitted(tenant,refundNo,evidenceRef)` | 仅HELD允许本地拒绝/撤销，释放额度→RELEASED；已派发不可凭人工操作释放 |
| `releaseVerifiedFailure(tenant,refundNo,evidenceRef)` | 仅SUBMITTED且调用方已验证原渠道不可再成功终态；释放额度→RELEASED |
| `confirmSucceeded(tenant,refundNo,providerRefundNo,amount,currency,providerSucceededAt,evidenceRef)` | 可信实际成功令HELD/SUBMITTED→SUCCEEDED，预占转已退、追加REFUND账本及Outbox；完整链路必须先耐久接收真实证据 |

`RELEASED`释放重放无资金副作用，不能再次派发或占款。`SUCCEEDED`重放核对金额、币种、渠道退款号及毫秒归一的成功时间；不同证据引用不覆盖原始证据。UNKNOWN在本层保持SUBMITTED与占款，完整退款应用会维护更细状态；普通超时、HTTP200、查不到、TTL到期都不是可靠失败。

HELD可接收实际成功，不以本地“尚未记录派发”否认真实资金；但调用者必须是完成验真与永久请求绑定的内部编排。RELEASED后出现矛盾成功抛`REFUND_TERMINAL_CONFLICT`，不再次扣已释放额度。未来耐久Inbox必须保留这类证据、转人工核对及受控资金修复，不能因Port报错丢掉真实渠道资金。这部分尚未实现，不声称当前有完整异常资金处置能力。

错误码：`INVALID_ARGUMENT`、`NOT_FOUND`、`REFUND_BINDING_CONFLICT`、`REFUND_AMOUNT_EXCEEDED`、`REFUND_FACT_CONFLICT`、`REFUND_TERMINAL_CONFLICT`、`STATE_CONFLICT`。无自带HTTP映射，待退款应用API统一交付。

## 不变量及可观察结果

成功与可靠释放竞争时只有一个本地终态提交；矛盾证据须由上层保留。不同订单竞争相同渠道退款号，唯一键失败者整事务回滚且继续预占。异常多付收款单独退款，不增加正常订单消费，也不替代多付审批政策。

此Port只改变资金：不清除everPaid、不倒换首单、不自动补库存、不标记实物已退回。V014退款应用已通过OrderRefundPort同事务维护订单财务状态及证据版本，净支付正式查询合同仍待完善。内部事件仍为`payment.refund-held/released/succeeded.internal.v0`，只携带原Outbox内部引用，不能冒充正式V1营销资格事件。

## 验证与发布边界

`FoundationTest#refundFunds*`使用隔离Testcontainers MySQL8.4，与共享dev_infra主版本一致。验证20并发10000额度、永久重放/绑定/租户、整事务回滚、SUBMITTED未知占款、重复成功与时间冲突、库存首单不变、渠道号唯一、终态竞争、跨订单首次预占、数据库3819/1452约束、旧租约回滚、混合部分退款和异常来源退款。通过结果以`docs/VERIFICATION.md`及原始Surefire报告为准，不能仅据方法存在认定验收通过。

V013不改已执行V001～V012；先隔离迁移和全量回归，再按既有授权迁移共享交易库。新增外键/唯一索引与修改CHECK可能等待元数据锁，生产发布需容量及锁等待评估、备份和窗口；不声称零停机DDL。历史无退款数据时无需回填退款hold。回滚不得删表或降级资金数据；一旦写入退款事实，旧程序及账本消费者对新方向的兼容必须先验证。

该Port没有实际渠道调用、退款任务通道、审批策略、用户限流或生产观测。少量并发测试不能证明100 RPS退款受理P99≤500ms、三高、容灾或真实供应商协议；这些原验收保持未完成。
