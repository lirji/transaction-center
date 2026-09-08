# 履约和退货操作合同

实现包括原计划的五个写入口及退货详情。当前验证进度以根CODEX_PROGRESS.md为准；真实WMS、仓储角色及生产身份尚需联调，不能以本地测试替代外部验真。

| 操作 | 路径 | 权限与前置 |
| --- | --- | --- |
| 整单实际交付 | POST /api/v1/trade/admin/orders/{orderNo}/fulfillments | trade.admin + trade.fulfillment.write，交付主体白名单 |
| 会员确认收货 | POST /api/v1/trade/orders/{orderNo}:confirm-receipt | 本人订单、全实物、已有真实交付；不接收自报证据和时间 |
| 服务完成 | POST /internal/v1/orders/{orderId}:complete | trade.fulfillment.complete，服务主体白名单及JWT租户 |
| 创建退货申请 | POST /api/v1/trade/admin/returns | trade.admin + trade.return.write，仓储主体白名单；申请不改库存 |
| 整批验收 | POST /api/v1/trade/admin/returns/{returnNo}:inspect | 相同仓储权限、强If-Match；实际收到后再调用 |
| 退货详情 | GET /api/v1/trade/admin/returns/{returnNo} | 本租户trade.admin，返回ETag和当前申请状态 |

所有写请求使用Idempotency-Key。交付与服务完成另带永久sourceRequestId，同来源事件不同HTTP键也返回首次结果；变更来源内容拒绝。首次服务器时间与来源回执同事务保存，缓存淘汰后不重新计时。会员确认固定绑定本人订单，已完成不会覆盖原完成来源和时间。交付只到FULFILLING，独立确认才到COMPLETED。

创建退货申请返回resourceNo=RMA申请号和版本0。验收输入result、restockQuantity、evidenceRef，数量含义如下：ACCEPTED整批全可售；PARTIAL整批已收到、只有部分可售；REJECTED整批已收到且可售0。部分到货应按实际批次数量创建独立申请，不用PARTIAL表达部分收到。所有批次总实收仍受原订单明细预算约束。

验收成功返回returnNo（RMA申请号）、receiptNo（独立RT验收事实号）、status=INSPECTED、version（RMA新版本）。不要把receiptNo作为申请ID。重复原请求不会再次回库；新请求旧版本返回412，缺版本428，已绑定来源内容变化409。申请数量不是库存预留保证，多申请最终仍不能超原实收预算。

默认TRADE_FULFILLMENT_ENABLED=false。启用需配置三个独立的主体摘要白名单：TRADE_FULFILLMENT_ALLOWED_DELIVERY_ACTOR_KEYS、TRADE_FULFILLMENT_ALLOWED_COMPLETION_ACTOR_KEYS、TRADE_FULFILLMENT_ALLOWED_RETURN_ACTOR_KEYS。摘要基于Actor.key的签发方与主体组合；认证、audience和tenant均来自JWT，不能使用客户端tenantId/memberId替代。scope与白名单必须同时满足；不要授权普通会员为交付或仓储来源。

新增交易准入暂停不影响既有订单交付、完成或退货；退款结果不会自动增加实物库存。来源回执、RMA、原订单/实收预算、库存和审计同事务，审计失败整体回滚。履约阶段375项全量通过；V029已在共享库迁移并校验成功，47业务表658字段注释完整。
