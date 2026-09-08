# 正常退款证据V1事务通知

沿用原退款成功合同及REFERRAL_EVIDENCE_V1_DESIGN的在途金额通知。与完成/取消事件合并为一个验证阶段，先集中定向测试，阶段结束一次全量。

在RefundService首次正常申请、释放、成功的现有订单证据版本更新处生成完整载荷。资金操作后重新使用funds.lockSource读取当前实收/成功累计/在途金额，不能用reserve前的source旧值。OrderRefundPort.recordRefundChange返回本次订单ID、会员及更新后的evidenceVersion；不引入独立RR补查或跨域Mapper。

trade.order-evidence-changed.v1统一ORDER聚合：data包括orderId/memberId/refundNo/refundAmount/paidAmount/cumulativeRefundedAmount/pendingRefundAmount/netPaidAmount/conservativeEligibleAmount/currency/evidenceVersion/changeReason。reason=REFUND_RESERVED/REFUND_RELEASED/REFUND_SUCCEEDED，金额满足正常实收=净额+成功退款，保守金额=净额-在途。occurredAt为本地接受该变化的毫秒时间，不伪装为渠道原始退款时间。

成功额外写trade.refund-succeeded.v1，相同完整data与订单version，不同eventId。原生必填本次退款/累计退款/净額/币种/会员/版本全部保留。消费者不能仅按订单version全局去重后丢弃另一事件类型；eventId为传输去重键，业务金额按快照版本推进。

重复申请/重复终态的既有提前返回不追加事件；审批批准及UNKNOWN保持在途，不需要额外改变金额通知。EXCESS/AFTER_CLOSE等异常补偿不写这两个正常事件，不推进正常证据版本。所有事件与资金、申请、组件预算、订单版本同事务，Outbox失败使全部回滚。旧internal.v0保留且禁止外发，无新DDL/HTTP/Kafka发送。

验证覆盖预占/拒绝/过期/可靠失败/成功，成功双事件同版本、重复不增、并发部分退款、异常补偿隔离与写失败回滚。原生事件仍需后续消费者乱序契约、C02/C03权威映射、可靠发布及生产签收。
