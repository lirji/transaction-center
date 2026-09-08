package com.lrj.trade.core;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
/** 订单cancelled的不可变V1事实；版本来自订单，时间来自已接受的业务事实。 */
public record OrderCancelledEvent(String eventId,String eventType,int schemaVersion,String tenantId,String aggregateType,
    String aggregateId,long aggregateVersion,Instant occurredAt,String producer,String traceId,Data data) implements V1EventEnvelope {
    /** 校验固定类型及聚合绑定，防止事件身份与载荷错配。 */
    public OrderCancelledEvent {
        uuid(eventId); uuid(traceId); id(tenantId); id(aggregateId);
        if(!"trade.order-cancelled.v1".equals(eventType) || schemaVersion!=1 || !"ORDER".equals(aggregateType)
            || !"transaction-center".equals(producer) || data==null || !aggregateId.equals(data.orderId())
            || aggregateVersion!=data.evidenceVersion() || !occurredAt.equals(data.cancelledAt())) throw new IllegalArgumentException("订单事件绑定不一致");
    }
    /** 事实时间不代表事务提交顺序，消费者以订单版本判断先后。 */
    public static OrderCancelledEvent create(long tenant,Data data) {
        Checks.positive(tenant); java.util.Objects.requireNonNull(data);
        return new OrderCancelledEvent(UUID.randomUUID().toString(),"trade.order-cancelled.v1",1,Long.toString(tenant),"ORDER",data.orderId(),
            data.evidenceVersion(),data.cancelledAt(),"transaction-center",TraceContext.correlationUuid(),data);
    }
    private static void id(String value) {
        long parsed=Long.parseLong(value); Checks.positive(parsed);
        if(!Long.toString(parsed).equals(value)) throw new IllegalArgumentException("事件编号必须为规范正十进制");
    }
    private static void uuid(String value) {
        if(!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("事件关联编号必须为规范UUID");
    }
    /** 只保留跨域必需事实，不携带用户原因原文、地址或渠道凭据。 */
    public record Data(String orderId,String memberId,Instant cancelledAt,long evidenceVersion,String reason) {
        /** 事件与数据库采用相同毫秒精度及订单版本。 */
        public Data {
            id(orderId); id(memberId); Checks.positive(evidenceVersion);
            if(cancelledAt==null || !cancelledAt.equals(cancelledAt.truncatedTo(ChronoUnit.MILLIS)) || !java.util.Set.of("CANCEL_REQUEST","PAYMENT_NONPAYABLE").contains(reason))
                throw new IllegalArgumentException("订单生命周期事实无效");
        }
    }
}
