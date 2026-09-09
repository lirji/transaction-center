# 正常退款申请与内部执行合同

> 时效说明（2026-09-08）：本文保留 V014 申请层切片，正文“尚未交付”及后台尚未运行的描述属于当时状态。当前已有 [审批 HTTP](REFUND_APPROVAL_HTTP.md)、[耐久回调](REFUND_CALLBACK_INBOX.md)、[恢复 Worker](REFUND_RECOVERY_WORKER.md)、异常补偿及履约退货入口。后台是否实际处理由部署开关决定，真实渠道仍需接入；总流程见 [业务说明](BUSINESS_GUIDE.md)。

状态：V014内部实现通过183项全量回归并已共享迁移；35表/469字段中文注释完整。沿用已批准交易中心完整计划及REFUND_IMPLEMENTATION_PLAN.md，不缩减TC20～25/27/29。实际验证结果以VERIFICATION.md为准；内部验证不替代下列外部交付边界。

## 交付边界

新增transaction-refund模块，组合OrderRefundPort与PaymentRefundPort。正常本人退款按订单定位唯一正常实收，不能由请求指定渠道或异常收款。退款申请、永久请求去重、组件额度、策略快照、内部审批结果、派发/成功/可靠失败/审批超时与订单财务状态在同一主库事务维护。

尚未交付：实际流程身份认证/签名回执、退款渠道适配/可信耐久Inbox/任务Worker、后台列表及受控审批HTTP、多付补偿申请、退货验收、正式V1事件和资格证据、生产容量。当前没有公开内部批准或成功接口。默认无策略进入待审批，但正常免审政策已可显式配置，不能以全人工审批替代最终范围。

## 流程与状态

1. 本人POST申请：验证总额/组件规范化→锁本人订单→支付Port锁正常实收→按成功+在途+本次累计评估政策→永久请求唯一键占位→资金hold/组件预占/明细→订单证据版本/审计/Outbox同事务提交。
2. 免审：READY；需审批：PENDING_APPROVAL，冻结approvalRef及24小时结果受理期限。未配置、策略币种不匹配或累计超过门槛必须审批；配置变更不追溯批准已创建申请。
3. 可信审批：关联号/审批人/决定版本/证据校验，不能自审自批。接受APPROVE→READY，REJECT→REJECTED并释放两层额度。重复同决定无副作用；旧/新冲突版本不能覆盖既有决定。未接受决定过期→EXPIRED，与“流程拒绝”分开保存，不伪造审批人；仅未派发申请可过期释放。
4. 事务内prepare：READY写支付hold及应用SUBMITTED，产生CREATE计划；SUBMITTED只产生QUERY原稳定商户退款号；待审批返回WAIT_APPROVAL；终态返回DONE。调用方必须同事务末尾fencing，提交后再调用渠道。该端口本身不调用网络，也不证明完整退款恢复已实现。
5. 耐久验真后成功：支付预占转已退、唯一账本、组件预占转已退、应用SUCCEEDED、订单财务状态/证据版本及Outbox同事务。可靠不可再成功终态→FAILED并释放两层预占。UNKNOWN保持SUBMITTED，占款不变；超时/查不到不构成失败。

数据库和prepare双重验证：需审批申请进入READY/SUBMITTED/SUCCEEDED/FAILED必须有APPROVE，REJECTED必须有REJECT；免审必须有非零策略版本及门槛。真实资金与本地状态矛盾时，完整退款Inbox必须保留证据再转受控核对；不能因内部端口拒绝而丢失真实资金。

## 数据、金额与锁

V014新增四张带全量中文注释的表：tc_refund_policy、tc_refund、tc_refund_component_budget、tc_refund_item；为订单明细增加复合引用索引。业务SQL全在RefundMapper、OrderRefundMapper及扩展PaymentRefundMapper XML，不允许退款直接读写其他域表。

- 永久请求键为tenant+applicantKey+sourceRequestId（当前来源于Idempotency-Key）；保留请求规范化摘要。即使tc_command已清理，同号仍只能对应原申请，不可换金额/订单/原因。
- 一申请最多100组件，各组件唯一且金额合计=申请金额。先验证请求类型、金额、安全整数上限与原订单归属，再使用资金和组件两层条件更新；任何一项失败全回滚。
- GOODS键绑定原订单项，金额不超过冻结payableAmount。quantity仅描述本次涉及数量，0表示金额调整，每次不超过原购买数量；累计退款受金额而非退货数量控制。实物退货须由未来履约/验收模块独立限制累计数量，不能以这里的quantity直接入库。
- FREIGHT/TAX只能退原订单已冻结费用，不能挂商品号或数量。目前正常订单首期这两项均为0，正额申请拒绝；模型支持真实冻结收费，但尚无收费下单能力，不宣称已完成生产运费/税退款案例。
- 配置免审门槛按同一正常实收累计成功+在途+本次比较，防拆单规避大额审批；先前可靠失败/拒绝释放的额度不计成功退款。
- 常规锁序订单→支付意图/实收→已有hold→退款聚合→组件→末尾fencing。创建时未提交的新聚合先占永久请求唯一键，仅新号继续创建hold；并发重放在同一订单锁串行，跨订单同键发现摘要冲突即回滚，不再获取对方订单锁。
- 读归属仅定位不变引用，不能用RR旧快照做状态决策；状态转换使用当前读。所有内部审批/执行方法MANDATORY，普通申请由Commands开启10秒事务。外部网络不在事务内。
- 正常退款预占、释放和成功推进订单version；仅成功金额决定PAID/PARTIALLY_REFUNDED/REFUNDED。履约状态、paidAt、首单/everPaid和库存不变。退款号/渠道号等内部对象默认日志脱敏。

## HTTP API

| 方法与路径 | 输入/权限 | 输出 |
|---|---|---|
| POST /api/v1/trade/orders/{orderNo}/refunds | 本人JWT；Idempotency-Key；amount、items、reason | 202 RefundView，只是本地受理 |
| GET /api/v1/trade/refunds/{refundNo} | 申请主体JWT | 当前状态、金额/币种、原因、策略/应用版本、组件；他人/他租户404 |
| GET /api/v1/trade/admin/refund-policy | trade.admin | currency、autoApproveLimit、version；未配置version=0、limit=0 |
| PATCH /api/v1/trade/admin/refund-policy | trade.admin、Idempotency-Key；currency、autoApproveLimit、expectedVersion | 本次命令应用的策略快照，重放不被后来配置改变；GET读取当前策略；CAS防覆盖，同事务审计 |

RefundSelection：componentType=GOODS/FREIGHT/TAX，orderItemId为字符串或非商品NULL，quantity非负，amount正安全整数。禁止附带tenant/receipt/channel/status等额外字段。请求总额及全部组件由后台再次核对，不能因为通过JSON验证而信任前端金额。完整机器合同见openapi.json；内部Java接口不列为HTTP能力。

## 验证与迁移

FoundationTest#refundApplication*覆盖双层额度、永久请求、20并发、规范化/归属/未收取费用、累计策略与冻结版本、审批职责分离/版本/期限、可靠失败与释放、唯一成功及财务证据、整体回滚、旧租约、SQL CHECK、HTTP权限/伪造状态。还须持续补高风险交错，并运行全量回归、独立只读复审后才迁移共享库。

V001～V013已执行不可修改，V014先隔离验证。生产DDL可能等待元数据锁，应评估窗口/备份/滚动兼容，不自动回滚删除退款数据。常驻退款恢复尚未接入，当前READY不会自动退款、到期不会自动扫描；运维不能直接改表来假装执行完成。

前端继续由Cursor实现；需要待审批/就绪/未知/已退/失败/超时及额度冲突页面状态，重试保留原键。所有Mock写入隔离数据库，不硬编码页面。完整裂变仍在交易内部完成后接续。
