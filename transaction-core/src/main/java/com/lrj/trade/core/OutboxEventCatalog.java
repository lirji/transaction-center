package com.lrj.trade.core;

import java.util.Set;
/** 固定发布合同目录；严格验证持久载荷后发送原字符串，不重建历史业务状态。 */
public final class OutboxEventCatalog {
    private OutboxEventCatalog() {}
    private static final Set<String> TYPES=Set.of("trade.member-registered.v1","trade.order-paid.v1",
        "trade.order-completed.v1","trade.order-cancelled.v1","trade.order-evidence-changed.v1",
        "trade.refund-succeeded.v1","trade.inventory-changed.v1");
    /** 不允许按任意事件名推导Topic，历史v0必须留在内部。 */
    public static Set<String> supportedTypes() { return TYPES; }
    /** SQL索引与完整头逐项匹配，损坏或跨租户拼接必须在网络发送之前拒绝。 */
    public static Message validate(long tenant,String eventId,String eventType,String aggregateId,String payload) {
        Checks.positive(tenant);
        if(payload==null || payload.length()>1_048_576 || eventType==null)
            throw new IllegalArgumentException("发布载荷缺失或超出上限");
        V1EventEnvelope event=switch(eventType) {
            case "trade.member-registered.v1" -> MemberEventCodec.decode(payload);
            case "trade.order-paid.v1" -> OrderPaidEventCodec.decode(payload);
            case "trade.order-completed.v1" -> OrderCompletedEventCodec.decode(payload);
            case "trade.order-cancelled.v1" -> OrderCancelledEventCodec.decode(payload);
            case "trade.order-evidence-changed.v1","trade.refund-succeeded.v1" -> RefundEvidenceEventCodec.decode(payload);
            case "trade.inventory-changed.v1" -> InventoryChangedEventCodec.decode(payload);
            default -> throw new IllegalArgumentException("事件类型不在发布白名单");
        };
        if(!Long.toString(tenant).equals(event.tenantId()) || !event.eventId().equals(eventId)
            || !event.eventType().equals(eventType) || !event.aggregateId().equals(aggregateId))
            throw new IllegalArgumentException("事件索引与载荷不一致");
        return new Message(event.eventId(),event.eventType(),event.tenantId()+":"+event.aggregateType()+":"+event.aggregateId(),payload,event.traceId());
    }
    /** 原始持久化JSON保持不变，包括空白和属性顺序，跨尝试使用相同事件键。 */
    public record Message(String eventId,String eventType,String partitionKey,String payload,String traceId) {}
}
