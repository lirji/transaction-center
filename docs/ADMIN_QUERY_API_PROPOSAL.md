# 管理端查询接口方案 v1（待审查）

日期：2026-09-08。状态：用户已授权实施；下文保留设计与原始需求对照，实际接口落点、兼容权限和验证边界见 [交付说明](ADMIN_QUERY_API.md) 与 `docs/openapi.json`。

原设计复核结论：本方案符合主要领域边界，但不是最初交易平台方案的完整复现。原需求、接口补齐及新增运维扩展的区分，以及尚需修订的问题，见第 9 节；审查时不能只以当前已实现接口为范围基线。

## 1. 目标与范围

让租户运营人员可以从订单列表进入订单、支付、收款、退款、退货和报价详情；让有额外权限的排障人员查看交易证据、资格评估与任务租约。

所有查询限于 JWT 所属租户的全部会员，不是跨租户平台查询。会员接口的本人归属检查保持原样。

本期新增 **15 个接口**，增强 **2 个已有接口**。其中 P0 新增 9 个、增强 2 个，P1 新增 6 个。不包含会员商城建设、退款审批按钮、人工改资金状态、通用任务重试或解锁。

## 2. 接口清单

以下路径统一省略 `/api/v1/trade/admin`。所有接口均为只读，包括使用 POST 表达复杂条件的资格评估。

| 编号 | 优先级 | 方法与路径 | 用途 |
|---|---|---|---|
| A01 | P0 | `GET /orders` | 当前租户订单列表和条件搜索 |
| A02 | P0 | `GET /orders/{orderNo}` | 订单及冻结商品、履约摘要 |
| A03 | P0 | `GET /payments` | 支付意图列表 |
| A04 | P0 | `GET /payments/{paymentNo}` | 支付意图详情 |
| A05 | P0 | `GET /payments/{paymentNo}/attempts` | 该支付意图的渠道尝试列表 |
| A06 | P0 | `GET /payment-receipts` | 实际收款列表，可筛选异常分类 |
| A07 | P0 | `GET /payment-receipts/{receiptNo}` | 收款详情与退款额度占用 |
| A08 | P0 | `GET /refunds/{refundNo}` | 管理端退款详情 |
| A09 | P0 | `GET /returns` | 退货申请列表 |
| A10 | P1 | `GET /quotes` | 报价列表 |
| A11 | P1 | `GET /quotes/{quoteId}` | 冻结报价明细 |
| A12 | P1 | `GET /orders/{orderNo}/referral-evidence` | 订单交易证据诊断 |
| A13 | P1 | `POST /members/{memberId}/eligibility:query` | 交易原生资格只读评估 |
| A14 | P1 | `GET /jobs` | 当前租户通用任务列表 |
| A15 | P1 | `GET /jobs/{jobId}` | 任务状态和租约详情 |
| E01 | P0 增强 | `GET /refunds` | 原队列增加订单、退款单、会员和时间筛选 |
| E02 | P0 增强 | `GET /returns/{returnNo}` | 保留已有字段，增加时间及实际验收数量摘要 |

不额外新建 `/abnormal-payments`：异常是一笔收款的 `disposition`，不是支付意图的一种新状态。也不新增每个订单的支付、退款、退货子列表路由，使用对应列表的 `orderNo` 筛选即可。

## 3. 通用合同

### 3.1 权限与租户

| 接口 | 必需权限（同时具备） |
|---|---|
| A01–A11、E01、E02 | `trade.admin` |
| A12、A13 | `trade.admin` + 新增 `trade.admin.evidence.read` |
| A14、A15 | `trade.admin` + 新增 `trade.admin.jobs.read` |

租户只取已验证 JWT 的 `tenant_id`，不接受请求参数、Header 或请求体覆盖。新增路由拒绝未知参数及未知 JSON 字段，避免筛选条件拼错却返回宽范围结果。所有 JOIN 同时匹配 `tenant_id` 和业务关联键。

缺少权限返回 403；有权限但资源不存在或属于其他租户，一律 404。列表中的外租户编号只得到空集合。嵌套尝试列表先检查父支付意图归属，父资源不属于当前租户返回 404。

证据管理入口使用独立、默认关闭的 `trade.admin-evidence.enabled` 开关和管理员权限校验；不调用内部 HTTP 接口，也不放宽现有 `EvidenceAccess` 的服务身份允许列表。可在同一应用内复用已验证的只读计算服务。内部入口原有开关和身份边界继续独立生效。管理员证据入口开启是后续明确的配置动作。

### 3.2 类型和分页

- `orderId`、`memberId`、`quoteId`、`jobId` 等 BIGINT 标识均为十进制字符串；业务单号是字符串。数字 ID 仅接受正整数规范文本，并检查 Java long 上界。
- 金额为币种最小单位的 JSON 整数，范围 `0..9007199254740991`；不返回浮点金额，不跨币种相加。版本、次数等沿用现有整数合同，并验证安全整数范围。
- 时间使用 UTC RFC 3339，毫秒精度。所有时间区间为 `[createdFrom, createdTo)`；两端同时提供，起点必须小于终点，建议首期最大 90 天。未提供时不隐含“最近 30 天”，避免旧单被无提示过滤。
- 编号和状态精确匹配，多个筛选条件取 AND。不提供自由文本 `keyword`、任意排序或 `%关键词%` 搜索；不通过手机号、姓名做交易全表搜索。
- 新增列表采用 `after`（十进制字符串，默认 `"0"`）和 `limit`（默认 20，1–100），按该资源主键升序；与现有 `after` 方向一致，首期不是最新优先。
- SQL 使用 `id > after ORDER BY id ASC LIMIT limit+1`，不做 offset 和默认 `COUNT(*)`。先选根资源分页，再批量补关联字段，不能直接对一对多 JOIN 结果分页。
- 新增列表返回 `Page<T>`：`items`、`nextAfter`（还有下一页时为最后一条返回记录的主键字符串，否则 null）、`hasMore`、`observedAt`。不返回总页数。
- 下一页需保持筛选条件不变；改变条件从 `after=0` 重查。跨页不是同一数据库快照，状态并发变化可能影响命中集合；本接口不是一致性导出协议。
- **E01 保持原数组响应、原 `cursor` 字段、原 `status=PENDING_APPROVAL` 默认值**，避免破坏现有控制台。需要不限定状态时显式传 `status=ALL`。不把已有数组接口统一改成 Page。

```json
{
  "items": [
    {
      "orderId": "10001",
      "orderNo": "ORD-EXAMPLE-001",
      "memberId": "20001",
      "status": "PAID",
      "financialStatus": "PAID",
      "payableAmount": 12900,
      "currency": "CNY",
      "createdAt": "2026-09-08T02:00:00.000Z",
      "version": 2
    }
  ],
  "nextAfter": null,
  "hasMore": false,
  "observedAt": "2026-09-08T03:00:00.000Z"
}
```

以上只展示核心字段，完整字段以各资源定义为准；示例不是种子数据。

### 3.3 读取语义与错误

普通详情和其子明细在同一主库只读事务读取；证据、资格复用已有独立 RR 快照与 3 秒事务超时。GET 不发起渠道网络查询、不修复数据、不领取任务、不自动标记到期或成功。`observedAt` 只是读取观察时间，不是可重放历史快照令牌。

统一错误形状沿用 `Errors.Failure`：`code/message/traceId/retryable`。401/403 由安全层产生时，以当前安全层行为为基线，不宣称已统一成同一 JSON。

| HTTP | 情况 |
|---|---|
| 400 `INVALID_ARGUMENT` | 非法枚举、ID、时间区间、limit、未知参数 |
| 401 / 403 | 未认证、令牌无效 / 权限不足 |
| 404 `NOT_FOUND` | 详情不存在或外租户资源 |
| 503 `EVIDENCE_DISABLED` | 管理证据入口未开启 |
| 503 `EVIDENCE_UNAVAILABLE` | 证据缺失或资金、首单事实不一致，不回退成“无资格” |
| 503 `ADMIN_QUERY_UNAVAILABLE`（新增） | 数据库超时等可重试读取故障，`retryable=true` |

新查询服务显式映射读取超时，避免沿用面向命令的 `RETRY_SAME_KEY` 文案。只读接口不要求 `Idempotency-Key`、`If-Match`。返回 `Cache-Control: no-store`。不设置一个仅基于订单 version 的聚合 ETag，因为支付、退款可能独立变化。

## 4. 各资源请求与响应

下表“公共参数”指新列表的 `after/limit/createdFrom/createdTo`。除明确必填外，筛选项可省略；未限定状态表示全部状态。详情中的可选关系及未发生时间显式为 null。

### 4.1 订单 A01 / A02

`GET /orders`：公共参数 + `orderNo/memberId/status/financialStatus/warehouseId`。

列表字段：`orderId/orderNo/memberId/quoteId/merchantNo/warehouseId/status/financialStatus/payableAmount/currency/expiresAt/paidAt/completedAt/createdAt/version`。

详情在列表字段基础上增加：

- `paymentStartedAt/cancelledAt/updatedAt`。
- `items[]`：`itemId/skuId/skuRevision/title/specification/stockType/quantity/unitPrice/payableAmount/returnedQuantity`。商品信息来自订单冻结快照；`returnedQuantity` 仅表示实际退货预算已消耗数量，不是可再次申请数量。
- `fulfillment`：可空对象 `fulfillmentNo/deliveredAt/completedAt/version`，来源于永久履约事实。
- `observedAt`。详情不嵌入无限增长的支付尝试、退款、退货列表；页面按 `orderNo` 分区查询。

不返回地址明文或密文、收件人、手机号、库存预占键、身份摘要、营销签名令牌。当前目标是查询及选单履约；如需要打印面单，应另行设计地址读取权限和审计。

`status`：`PENDING_PAYMENT/CLOSING/CANCELLED/PAID/FULFILLING/COMPLETED`。
`financialStatus`：`UNPAID/NO_PAYMENT_REQUIRED/PAID/PARTIALLY_REFUNDED/REFUNDED`。

零元订单 `paidAt=null` 可以是正常情况，不能由此判定订单失败。

### 4.2 支付意图 A03 / A04

`GET /payments`：公共参数 + `paymentNo/orderNo/memberId/status`。

列表字段：`paymentNo/orderId/orderNo/memberId/status/expectedAmount/currency/activeAttemptNo/createdAt/updatedAt/version`。

详情增加 `succeededReceiptNo/closeFenceAt/observedAt`；正常成功收款以已持久化的 `succeeded_receipt_id` 关联业务编号，不取“最近一笔收款”。没有成功指针时为 null。

`status`：`READY/PENDING/SUCCEEDED/CLOSED/REVIEW`。`closeFenceAt` 是禁止新尝试的本地屏障，不是渠道可靠关闭证明。

### 4.3 支付尝试 A05

`GET /payments/{paymentNo}/attempts`：`after/limit/status`，不加时间窗口；范围已限定为单个支付意图。

字段：`attemptId/paymentNo/attemptNo/channelId/channelCode/status/submittedAt/closedAt/hasCloseEvidence/createdAt/updatedAt/version`。

`attemptNo` 映射 `merchant_request_no`，与支付意图的 `activeAttemptNo` 同一种编号。`channelId` 是管理渠道资源 ID，不是凭据。`hasCloseEvidence` 只表示存在证明，不提供原证据引用。

`status`：`PREPARED/UNKNOWN/SUCCEEDED/CLOSED`。`UNKNOWN` 不等于失败，`submittedAt` 不证明渠道已受理。每条即为完整的安全尝试视图，首期不再新增尝试详情接口。

### 4.4 收款 A06 / A07

`GET /payment-receipts`：公共参数 + `receiptNo/paymentNo/orderNo/channelCode/disposition/abnormalOnly`。

`abnormalOnly` 默认 false，表示不过滤正常与异常；true 表示 `disposition != NORMAL`。若同时给 `disposition=NORMAL` 和 `abnormalOnly=true` 则返回 400；其他指定异常分类与 true 可共同使用。

列表字段：`receiptNo/paymentNo/orderNo/attemptNo/channelCode/amount/currency/disposition/providerPaidAt/confirmedAt/createdAt`。

详情增加 `refundedAmount/refundReservedAmount/unreservedAmount/observedAt`，其中 `unreservedAmount=amount-refundedAmount-refundReservedAmount`；该值只是本笔收款未占用金额，不是退款操作授权，也不保证符合订单退款政策。

`disposition`：`NORMAL/EXCESS/AMOUNT_MISMATCH/AFTER_CLOSE/AFTER_TERMINAL`。异常收款属于真实资金事实，不能直接累计为订单有效支付，也不能跨币种合计。

首期不返回渠道商户号、完整渠道流水号、回调原文、签名、证据引用、付款参数或支付链接。使用本地 `receiptNo` 做下钻；按外部渠道流水号检索不在本版范围。

### 4.5 退款 A08 / E01

E01 新增 `refundNo/orderNo/memberId/createdFrom/createdTo`；`status` 支持现有七种状态和新增查询值 `ALL`。`ALL` 只取消状态筛选，不是持久化状态。其余分页、数组响应和队列字段保持原样。

`GET /refunds/{refundNo}` 字段：`refundNo/orderNo/memberId/amount/currency/status/approvalRef/approvalDeadline/policyVersion/version/createdAt/observedAt/items[]`。

`items[]`：`componentType/orderItemId/quantity/amount`，其中非商品分项的 `orderItemId` 可为 null。`componentType` 复用 `GOODS/FREIGHT/TAX`。

首期详情不返回用户自由文本 reason、审批人身份摘要、原始审批回执、渠道退款号和内部证据。退款原因可能含个人信息，不能把会员 `RefundView` 原样暴露给管理员。若运营审查需要原因正文，应作为扩展字段及权限另审。

`status`：`PENDING_APPROVAL/READY/SUBMITTED/SUCCEEDED/REJECTED/FAILED/EXPIRED`。

退款批准/拒绝保持现有 `POST /api/v1/trade/integrations/refund-approvals` 的独立 scope、可信签名回执和服务身份验证。本方案不新增 `/approve`、`/reject`，也不将 `trade.admin` 提升为审批身份。

### 4.6 退货 A09 / E02

`GET /returns`：公共参数 + `returnNo/orderNo/memberId/orderItemId/warehouseId/status`。

字段：`returnId/returnNo/orderNo/memberId/orderItemId/warehouseId/quantity/status/receiptNo/version/createdAt/updatedAt`。分页根表是 **tc_return_request**，不是已实收表 tc_return_receipt，否则待验收申请会遗漏。

E02 保留原详情的全部字段，增加 `createdAt/updatedAt/inspection`。`inspection` 尚未验收时为 null；已验收时为 `receiptNo/receivedQuantity/restockQuantity/recordedAt`，通过申请的 `receipt_no` 关联同租户 `tc_return_receipt.return_no`。`recordedAt` 取实收表 `created_at`，表示本地登记时间，不冒充实际到货时间。

申请状态只有 `OPEN/INSPECTED`。验收命令中的 `ACCEPTED/PARTIAL/REJECTED` 不是申请状态，不能用于 `status` 筛选。现有实收表没有独立保存该结果枚举，详情不从数量猜造 `inspectionResult`。实收与退款保持独立，不展示虚构的一对一退款关系。

### 4.7 报价 A10 / A11

`GET /quotes`：公共参数 + `quoteId/memberId`，首期不提供派生状态筛选。

列表字段：`quoteId/memberId/payableAmount/currency/pricingPolicy/createdAt/expiresAt`。详情增加既有 `QuoteItem` 的完整安全快照字段及 `observedAt`。

报价本身没有已使用/过期状态列，不伪造 `USED`。页面可以按服务端 `observedAt >= expiresAt` 显示“已过期”，但“未过期”不保证当前可下单，商户及商品等规则仍由创建订单接口判定。订单已经有 `quoteId`，可以直接进入报价详情。

### 4.8 订单证据 A12

输入：路径 `orderNo`，服务端在本租户解析 orderId。

输出：`orderNo` 及现有安全证据字段：`orderId/memberId/evidenceVersion/firstOrderPolicy/isFirstConfirmedPaidOrder/paidAmount/refundedAmount/pendingRefundAmount/netPaidAmount/conservativeEligibleAmount/currency/paidAt/completedAt/asOf`。

完全沿用 `OrderReferralEvidenceService` 的资金与首单一致性校验、公式及快照语义。证据不一致返回 503，不拼凑零值或自行判断首单。此响应是运营诊断数据，不含营销签名，也不产生奖励资格确认或预占。

### 4.9 资格评估 A13

```http
POST /api/v1/trade/admin/members/20001/eligibility:query
Content-Type: application/json
```

```json
{
  "policyVersion": "REGISTERED_DURING_CAMPAIGN_V1",
  "campaignStartAt": "2026-09-01T00:00:00.000Z",
  "evaluatedAt": "2026-09-08T03:00:00.000Z"
}
```

`policyVersion` 必填，仅支持已有 `REGISTERED_DURING_CAMPAIGN_V1` 和 `NEVER_CONFIRMED_PAID_V1`。前者必须提供 `campaignStartAt`；`evaluatedAt` 可省略为当前快照评估时间，禁止未来时点。会员 ID 仅取路径。

响应沿用 `status/evidence` 形状；成功 `status=READY`。`evidence` 为 `memberId/registeredAt/everPaid/firstPaidOrderNo/factVersion/eligible/reasonCodes/evaluatedAt/policyVersion/everPaidAtEvaluation/observedAt`。会员不存在或外租户统一转换为 404，不把不存在当新客。

该接口只回答交易原生策略条件，不回答完整营销活动资格、活动配额、奖励发放或外部 canonicalSubject 映射。不是资格列表，更不是历史资格账本。

### 4.10 任务 A14 / A15

`GET /jobs`：公共参数 + `jobType/status/leaseExpired`。`leaseExpired` 为可选布尔，true 仅筛选 `RUNNING AND lease_until <= 主库当前时间`，false 筛选其余任务；省略则不筛选。

列表字段：`jobId/jobType/status/attempts/nextRunAt/leaseUntil/leaseExpired/lastError/createdAt/updatedAt/observedAt`。每次请求使用同一个主库时间计算过期标志。

详情增加 `version/businessReference`。`businessReference` 为可空对象 `resourceType/resourceNo`；仅在固定 jobType 适配器成功解析并验证本租户资源后返回安全业务编号，否则 null。

不返回原 `business_key`、owner、fencing token、traceParent 或任务载荷；不提供强制领取、续租、解锁、改状态。`leaseExpired` 是观察值，任务被其他 worker 接管后可能立即变化。

`status`：`READY/RUNNING/DONE/REVIEW`。
`jobType`：`PAYMENT_RECOVER/PAYMENT_CALLBACK/ORDER_RESOLVE/REFUND_CALLBACK/REFUND_RECOVER/REFUND_COMPENSATE/OUTBOX_PUBLISH/RECON_QUERY`。

Outbox 现有 `/outbox-publications/tasks` 及受控重放保持原状；通用任务页用于排障，不替代 Outbox 业务操作页面。

## 5. 实现方案与数据访问

### 5.1 备选方案与建议

以下为本轮基于源码的单人设计比较，评分为设计判断，不是性能实测或独立多 Agent 审查。

| 方案 | 评分 / 10 | 取舍 |
|---|---|---|
| 在会员查询中增加管理员分支 | 5 | 初始代码少，但本人过滤与租户全员查询混合，容易扩大原授权边界 |
| 独立 Admin Query Service + 安全 DTO + 现有库只读 Mapper | 9 | 推荐；权限显式、可复用表和领域只读 Port，增加少量查询类 |
| 新建异步查询投影、搜索服务 | 6 | 能承载复杂搜索，但引入同步延迟、补数和额外部署；当前精确过滤不需要 |

推荐第二种。第一版直接读主库，不引入新中间件、新业务表或通用搜索引擎。金额聚合、证据判断复用现有只读 Port，不复制领域裁决逻辑。

### 5.2 模块改动边界

| 模块 | 拟新增或调整 |
|---|---|
| transaction-server | AdminOrder/Payment/Refund/Quote/Job/Evidence 查询 Controller；AdminEvidenceAccess；SecurityConfiguration 的精确权限规则放在 admin 通配规则之前；Errors 新查询错误映射 |
| transaction-order | AdminOrderQueryService、AdminReturnQueryService、安全 DTO、只读 Mapper；履约和退货数量投影 |
| transaction-payment | AdminPaymentQueryService、AdminReceiptQueryService、意图/尝试/收款安全投影 |
| transaction-refund | AdminRefundQueryService；原 listQueue 委托兼容筛选扩展，不改审批及退款命令 |
| transaction-catalog | AdminQuoteQueryService，独立 tenant-only 管理查询；保留 findOwn |
| transaction-jobs | AdminJobQueryService；不复用领取/续租 Mapper 方法 |
| transaction-core | 若需要，定义共用 Page DTO 和跨模块只读查询 Port；不放 Controller 业务逻辑 |
| docs / 测试 | 审查通过后补 OpenAPI、接口文档、权限与数据库集成测试；交给 Cursor 接页面 |

列表明细查询禁止 N+1；支付列表不捎带尝试集合。Controller 不访问 Mapper，不把持久化实体直接序列化。跨域关联由受控只读 Port 或明确只读投影处理，不反向调用会员身份查询。

### 5.3 索引和迁移

现有 order/quote/payment/refund 具备部分租户编号或会员索引，但不足以覆盖新增状态、时间筛选；return_request 尤其缺少租户列表索引。实施时先对最终 SQL 做 EXPLAIN，再确定新增索引，不为筛选条件的任意排列建索引。

候选索引前缀：订单 `(tenant_id,status,id)`、`(tenant_id,financial_status,id)`；意图 `(tenant_id,status,id)`；尝试 `(tenant_id,intent_id,id)`；收款 `(tenant_id,disposition,id)`、`(tenant_id,order_id,id)`；退货 `(tenant_id,status,id)`、`(tenant_id,order_id,id)`；任务 `(tenant_id,status,id)`、`(tenant_id,job_type,status,id)`。缺少的 `(tenant_id,id)` 列表入口及 `(tenant_id,created_at,id)` 时间过滤索引另按实际计划评估，已有等价索引不重复创建。

时间范围过滤加主键排序可能产生额外扫描或排序；LIMIT 不能证明查询便宜。组合筛选必须覆盖稀疏命中与大租户数据的执行计划、扫描量和超时测试；无法满足预算时收紧支持组合或调整索引后再交付。

只追加新的 Flyway 迁移，不改历史 V001–V032；实施时重新核对最新迁移号，不能假定 V033 仍可用。当前不计划建表；若后续确需建表，表与每列必须有中文 COMMENT。

## 6. 验收条件

1. 同租户管理员可以查其他会员的数据；同一个 JWT 调会员接口仍受本人归属限制。跨租户列表、详情、嵌套尝试和关联 JOIN 均不泄漏。
2. 新增证据/任务权限缺失时 403；证据开关关闭时 503。管理员入口不会使内部服务入口允许列表失效，管理权限也不能提交退款批准。
3. 编号、金额、版本的 JSON 类型正确；非法枚举、未知字段、超界 limit 和时间条件得到 400。
4. 游标空页、末页、精确命中、AND 冲突、并发状态变化符合合同；根记录不会因 JOIN 重复或漏掉待验收退货。
5. 零元订单、未知支付、重复收款、异常币种、部分退款、退款占用及退货验收分别按持久事实呈现。
6. 管理 DTO 不含地址、凭据、回调原文、审批人摘要、原始租约凭据；日志也不记录请求正文和敏感自由文本。
7. GET/资格 POST 不修改交易状态、不产生渠道调用、不领取任务；访问审计与业务写入明确区分。证据/任务访问记录 tenant、actor 摘要、资源类型/编号、结果码和 traceId，不记录证据全文。
8. 管理读取超时正确返回可重试查询故障。列表、详情和证据查询预算首期建议 3 秒；性能指标以真实 MySQL 验证结果交付，不在设计阶段宣称已达标。
9. E01 默认调用返回与旧版相同的数组、字段和默认待审批队列；E02 新字段通过现有前端 schema 兼容性检查。旧会员、审批、Outbox 接口回归通过。
10. OpenAPI 与 Controller 合同测试、租户安全集成测试、真实 MySQL 分页/关联/索引验证通过后，才标注“前端可接入”。测试数据通过隔离库种子脚本写入，不硬编码进正式页面。

## 7. 实施顺序与本轮审查点

实施顺序：先 P0 订单与退货，再支付/收款/退款；随后 P1 报价、证据与任务。每个阶段交付对应 OpenAPI、安全测试和前端交接说明。查询改动按可独立测试的任务分支与提交组织；本轮不创建代码提交。

建议本轮确认四项设计选择：

1. **范围**：先交付 P0 的 9 个新接口及 2 个增强，P1 的 6 个接口随后完成。
2. **分页**：新接口 Page + after 主键升序；旧退款队列保持数组兼容。若要求最新优先，应在实现前改定 `before` 降序合同。
3. **权限**：日常查询沿用 trade.admin，证据和通用任务查询增加独立 scope。
4. **字段边界**：首期不提供地址、退款原因正文及完整渠道流水号；若这些是运营必要信息，应在审查时明确，补字段权限及脱敏方案后再实现。

## 8. 核查依据与当前限制

- `transaction-server/.../TradeController.java:229` 起：报价、会员订单和支付路由；`:310` 起：退款详情、签名审批入口、管理队列。
- `transaction-server/.../SecurityConfiguration.java:48` 起：内部证据、审批、管理路由的权限规则。
- `transaction-server/.../EvidenceAccess.java:8` 起：内部证据开关及可信服务身份边界。
- `transaction-server/.../CustomerEligibilityService.java:29` 起、`OrderReferralEvidenceService.java:43` 起：资格与证据的现有快照和判断语义。
- `transaction-server/src/main/resources/db/migration/V004__*.sql`、`V005__order_foundation.sql`、`V007__payment_core.sql`、`V008__job_leases.sql`、`V014__refund_application.sql`、`V021__return_receipts.sql`、`V027__reconciliation_query_operations.sql`、`V029__fulfillment_ingress.sql`：持久模型、状态及索引。
- `transaction-order/.../FulfillmentIngressMapper.xml`：RMA 查询来自申请表；`transaction-jobs/.../OutboxTaskQueryService.java`：已有 Outbox 任务脱敏投影。

本轮仅做源码、SQL、OpenAPI 的静态核查与方案自查，没有改业务代码、执行迁移、连接 IAM 配置或运行接口测试。方案字段是拟新增合同，不代表当前 API 已返回。

## 9. 与最初交易平台设计的对照（复核补充）

原始基线位于同级营销仓库 `docs/plans/transaction-center-20260907/`，不是当前控制台 README。依据：

- [原始总纲](../../marketing-lowcode-platform/docs/plans/transaction-center-20260907/FINAL_PLAN.md)：第 2 节领域范围、第 4 节架构、第 5 节不变量、第 8 节安全、第 10 节前端交付范围。
- [原始 API 合同](../../marketing-lowcode-platform/docs/plans/transaction-center-20260907/EXTERNAL_CONTRACTS_AND_API.md)：第 1 节公共协议、第 2 节外部 API、第 3 节内部事实合同。
- [原始验收](../../marketing-lowcode-platform/docs/plans/transaction-center-20260907/ACCEPTANCE.md)：TC-01–TC-32 及性能、安全和发布门禁仍是整体项目要求，本查询方案不替代它们。

| 本版能力 | 与原设计的关系 |
|---|---|
| A03 管理支付列表、E01 退款列表时间筛选 | 原 API 表明确要求，属于补齐原需求，不是新增业务 |
| A08 管理退款详情 | 原 GET /refunds/{refundNo} 标注 M/A；当前实现只支持会员。本版采用独立 admin 路径恢复管理员读取能力，属于路由细化 |
| A01/A02 订单查询、A04 支付详情 | 原总纲前端交付明确包含订单／支付／退款详情；原 API 表未完整展开这些 admin 路由，属于使原运营需求可落地的接口补齐 |
| A05 支付尝试、A06/A07 收款查询 | 原领域明确包含尝试、全部真实收款及异常资金处理；单独的运营读取路由是本版细化，不能声称原表逐条列出 |
| A09/E02 退货查询 | 原方案有退货创建、验收及库存事实；列表和增强详情是运营配套补充 |
| A10/A11 管理报价查询 | 原方案有服务端报价，未明确管理报价浏览；属于本次新增的管理读取扩展 |
| A12/A13 管理证据和资格查询 | 原方案仅列内部服务入口；管理员可读入口及新增 scope 是本次扩展，需要单独审查授权边界 |
| A14/A15 通用任务查询 | 原方案要求租约和 fencing 正确性，没有列管理员任务 HTTP 接口；属于本次运维扩展 |

### 9.1 不能忽略的原设计差异

1. **原方案确实有退款 approve/reject**：原 API 表明确列出后台批准、拒绝，且要求流程约束、不可自审自批，同时列出流程审批结果内部入口。后续实现收敛为签名回执接入（见 REFUND_APPROVAL_HTTP.md）。当前“控制台没有按钮”的测试只能说明实现现状，不能证明原需求已取消。需要登记审批交互由流程平台承接还是后续补受控管理入口，以及替代合同的验收证据；禁止直接增加可任意改退款状态的按钮。
2. **原退款详情要求失败／未知原因**：A08 目前只有状态等字段，未提供安全的失败／未知原因，尚不完整。后续修订需区分用户申请 reason 与系统处理原因，补充结构化 `processingReasonCode`（无可靠来源时 null）及其来源和脱敏映射，不能用状态硬猜原因。是否显示申请原因正文另定权限。
3. **权限仍需业务操作矩阵**：原方案要求按租户和业务操作鉴权；统一 trade.admin 只是与现实现兼容的初稿。应明确哪些角色可查订单、支付、退款，不能把当前粗粒度 scope 当成最终权限矩阵。证据、任务新增 scope 也不是原方案已冻结的名称。
4. **版本合同需补齐或登记例外**：原公共协议要求列表与详情返回版本。本草案报价、收款列表及任务列表等未全部列 version；正式接口定稿前补齐资源版本，并明确它不代表关联聚合的统一版本。
5. **三高门禁继续保留**：3 秒查询超时是保护预算，不是响应性能达标承诺；需补租户查询配额、并发限制及与交易写入争用验证，不以分页和索引建议代替原性能／容量验收。
6. **最初方案包含会员端／管理端**：总纲架构及前端分工有两类入口。当前控制台排除会员商城仅是该应用边界，不能据此删除整体会员端交付依赖。

另外，原合同还有 M/A 退款申请和受审批约束的对账修复命令；本版查询接口不覆盖这些写入能力，也不据此宣称最初交易平台已全部实现。它们应在全量原需求审计中单独核对。

本次复核仅补充设计溯源与差异，未将上述待修订字段或审批流程视为已获批准、已实现。
