# 后续订单生命周期与退款V1接线要点

核对当前OrderService/OrderFulfillmentService/RefundService写路径所得，实施前仍需独立审查。

- order-completed只能在独立商业完成写入之后生成；零元服务完成可产生完成事件，但不能补造order-paid或消费首单。
- order-cancelled有无支付许可直接取消、可靠渠道关闭后取消两条路径。CLOSING不能提前发终态事件。事件reason使用稳定分类码，不能把用户取消理由原文带入跨域消息。
- RefundService.request首次正常退款持有原实收source，资金reserve之后原source.reservedAmount已经过时；构造累计载荷要重读当前锁内source，不能用旧值拼新版本。
- RefundService.refreshOrder在释放与成功后重新锁实收；仅NORMAL分支推进订单证据。OrderRefundPort.recordRefundChange可返回本次锁内会员/订单/版本，用来构造完整本地V1，不做独立RR补查。
- 正常预占、拒绝/审批过期/可靠渠道失败释放、成功均需trade.order-evidence-changed.v1；成功另有trade.refund-succeeded.v1，包含本次退款与累计正常退款/净额。两个事件共享同一订单证据版本但eventId不同，消费者不能仅按版本丢弃不同事件类型所需的业务动作。
- 退款编排重复返回路径不生成新事件；SUBMITTED未知不释放额度。异常EXCESS/AFTER_CLOSE等补偿不推进正常证据版本，不能进入正常退款累计事件。
- 所有金额来自当前持锁来源；ORDER聚合版本不是退款申请版本。发布时原样使用已保存JSON，后续查询或退款不会重写历史事件。

验证应覆盖预占/释放/成功与版本载荷一致、拒绝/过期/UNKNOWN、并发部分退款及重放、异常补偿隔离、外层/Outbox失败全回滚，以及乱序消费者最新证据不回退。可靠发布和生产签署仍独立待验。
