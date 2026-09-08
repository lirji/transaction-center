package com.lrj.trade.core;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
/** 正常订单退款累计证据；异常补偿不能进入该事件类型。 */
public record RefundEvidenceEvent(String eventId,String eventType,int schemaVersion,String tenantId,String aggregateType,
    String aggregateId,long aggregateVersion,Instant occurredAt,String producer,String traceId,Data data) implements V1EventEnvelope {
    /** 变更通知和成功事实共享金额快照，传输身份各自永久唯一。 */
    public RefundEvidenceEvent {
        uuid(eventId); uuid(traceId); id(tenantId); id(aggregateId);
        if(!Set.of("trade.order-evidence-changed.v1","trade.refund-succeeded.v1").contains(eventType)
            || schemaVersion!=1 || !"ORDER".equals(aggregateType) || !"transaction-center".equals(producer)
            || data==null || !aggregateId.equals(data.orderId()) || aggregateVersion!=data.evidenceVersion()
            || occurredAt==null || !occurredAt.equals(occurredAt.truncatedTo(ChronoUnit.MILLIS))
            || (eventType.equals("trade.refund-succeeded.v1") && !data.changeReason().equals("REFUND_SUCCEEDED")))
            throw new IllegalArgumentException("退款证据事件绑定不一致");
    }
    /** 本地接受时间随载荷存储，不把发布时间或渠道原始时间混入。 */
    public static RefundEvidenceEvent create(long tenant,String type,Instant at,Data data) {
        Checks.positive(tenant); Objects.requireNonNull(data);
        return new RefundEvidenceEvent(UUID.randomUUID().toString(),type,1,Long.toString(tenant),"ORDER",data.orderId(),
            data.evidenceVersion(),at,"transaction-center",TraceContext.correlationUuid(),data);
    }
    private static void id(String value) {
        long parsed=Long.parseLong(value); Checks.positive(parsed);
        if(!Long.toString(parsed).equals(value)) throw new IllegalArgumentException("事件编号必须为规范正十进制");
    }
    private static void uuid(String value) {
        if(!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("事件关联编号必须为规范UUID");
    }
    /** 完整正常实收累计快照，消费者不依赖消息到达顺序累加退款。 */
    public record Data(String orderId,String memberId,String refundNo,long refundAmount,long paidAmount,long cumulativeRefundedAmount,
        long pendingRefundAmount,long netPaidAmount,long conservativeEligibleAmount,String currency,long evidenceVersion,String changeReason) {
        /** 失败关闭，防止在途被算成成功或异常补偿提高正常消费金额。 */
        public Data {
            id(orderId); id(memberId); Checks.text(refundNo,64); Checks.positive(evidenceVersion);
            if(!refundNo.startsWith("RF-") || paidAmount<=0 || paidAmount>9007199254740991L || refundAmount<=0 || refundAmount>paidAmount
                || cumulativeRefundedAmount<0 || cumulativeRefundedAmount>paidAmount || pendingRefundAmount<0
                || pendingRefundAmount>paidAmount-cumulativeRefundedAmount || netPaidAmount!=paidAmount-cumulativeRefundedAmount
                || conservativeEligibleAmount!=netPaidAmount-pendingRefundAmount || currency==null || !currency.matches("[A-Z]{3}")
                || !Set.of("REFUND_RESERVED","REFUND_RELEASED","REFUND_SUCCEEDED").contains(changeReason)
                || (changeReason.equals("REFUND_SUCCEEDED") && cumulativeRefundedAmount<refundAmount))
                throw new IllegalArgumentException("退款累计证据金额或原因无效");
        }
    }
}
