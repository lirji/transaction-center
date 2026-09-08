package com.lrj.trade.core;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 正常足额收款V1；首单和订单版本来自当前写事务，绝不按发布时状态重建。 */
public record OrderPaidEvent(String eventId,String eventType,int schemaVersion,String tenantId,String aggregateType,
    String aggregateId,long aggregateVersion,Instant occurredAt,String producer,String traceId,Data data) implements V1EventEnvelope {
    /** 聚合和载荷必须同源，错误版本或绑定不允许进入Outbox。 */
    public OrderPaidEvent {
        uuid(eventId); uuid(traceId); id(tenantId); id(aggregateId);
        if(!"trade.order-paid.v1".equals(eventType) || schemaVersion!=1 || !"ORDER".equals(aggregateType)
            || !"transaction-center".equals(producer) || data==null || !aggregateId.equals(data.orderId())
            || aggregateVersion!=data.evidenceVersion() || !occurredAt.equals(data.paidAt())) throw new IllegalArgumentException("支付事件绑定不一致");
    }
    /** 只接收已锁定且成功转换后的数据；随机身份只生成一次并持久保存。 */
    public static OrderPaidEvent create(long tenant,Data data) {
        Checks.positive(tenant); java.util.Objects.requireNonNull(data);
        return new OrderPaidEvent(UUID.randomUUID().toString(),"trade.order-paid.v1",1,Long.toString(tenant),"ORDER",data.orderId(),
            data.evidenceVersion(),data.paidAt(),"transaction-center",TraceContext.correlationUuid(),data);
    }
    private static void id(String value) {
        long parsed=Long.parseLong(value); Checks.positive(parsed);
        if(!Long.toString(parsed).equals(value)) throw new IllegalArgumentException("事件编号必须为规范正十进制");
    }
    private static void uuid(String value) {
        if(!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("事件关联编号必须为规范UUID");
    }
    /** paidAt是本地主库确认时间；receiptNo不是渠道交易号，不携带渠道凭据。 */
    public record Data(String orderId,String memberId,String receiptNo,long paidAmount,String currency,Instant paidAt,
        String firstOrderPolicy,boolean isFirstConfirmedPaidOrder,long evidenceVersion) {
        /** 零元或异常资金不能构造正常订单支付事件。 */
        public Data {
            id(orderId); id(memberId); Checks.text(receiptNo,64); Checks.positive(evidenceVersion);
            if(!receiptNo.startsWith("RC-") || paidAmount<=0 || paidAmount>9007199254740991L || currency==null
                || !currency.matches("[A-Z]{3}") || paidAt==null || !paidAt.equals(paidAt.truncatedTo(ChronoUnit.MILLIS))
                || !"FIRST_CONFIRMED_PAID_ORDER_V1".equals(firstOrderPolicy)) throw new IllegalArgumentException("正常支付事件事实无效");
        }
    }
}
