# 独立完成与可靠取消V1

在已验证履约/关单行为上追加完整事务事件，不改变业务状态机或开放新HTTP。

- OrderFulfillmentService.complete首次写入独立商业完成事实并更新订单后，追加trade.order-completed.v1；重复来源事件只返回原事实。data=orderId/memberId/completedAt/evidenceVersion，ORDER聚合版本等于该次order.version+1，occurredAt=completedAt。交付本身不产生商业完成事件。
- OrderService.cancel仅无支付许可且真正到CANCELLED时追加trade.order-cancelled.v1；有未决支付进入CLOSING时不产生终态事件。
- OrderService.completePaymentClose只有渠道/本地可靠不可支付屏障成立后首次取消才追加同类型V1；永久重复不追加。data=orderId/memberId/cancelledAt/reason/evidenceVersion，时间/版本与本次订单更新一致。
- reason只取稳定分类CANCEL_REQUEST/PAYMENT_NONPAYABLE，不跨域传输用户原因原文、收货信息或渠道凭据。原始原因继续由已有内部订单/审计按授权持有。
- 零元服务可以商业完成，产生completed而不产生paid/消费首单；退款后完成历史与原事件载荷保持不变。C02/C03结算语义仍需显式adapter和签收，不能只改字段名。
- 类型化事件与各自严格codec、schema/golden fixtures共用Outbox现有V1插入，无新DDL；仍保留internal.v0，不发送Kafka。

测试覆盖真实交付前不完成、永久重复/退款后载荷不变；直接取消、CLOSING无终态、可靠关闭后恰一份V1；取消原因原文不泄露、外层/事件写入失败全回滚、零元服务仅完成。全量与独立审查后再推进退款与库存事件和可靠发布。
