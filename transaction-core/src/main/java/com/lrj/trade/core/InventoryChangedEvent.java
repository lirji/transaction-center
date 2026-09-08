package com.lrj.trade.core;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
/** 每个仓库SKU的完整库存快照，不能作为调拨新增量累加。 */
public record InventoryChangedEvent(String eventId,String eventType,int schemaVersion,String tenantId,String aggregateType,
    String aggregateId,long aggregateVersion,Instant occurredAt,String producer,String traceId,Data data) implements V1EventEnvelope {
    /** 聚合键严格绑定仓库SKU，余额版本决定先后而非消息到达时间。 */
    public InventoryChangedEvent {
        uuid(eventId); uuid(traceId); id(tenantId);
        if(!"trade.inventory-changed.v1".equals(eventType) || schemaVersion!=1 || !"INVENTORY_BALANCE".equals(aggregateType)
            || !"transaction-center".equals(producer) || data==null || !aggregateId.equals(data.warehouseId()+":"+data.skuId())
            || aggregateVersion!=data.balanceVersion() || occurredAt==null || !occurredAt.equals(occurredAt.truncatedTo(ChronoUnit.MILLIS)))
            throw new IllegalArgumentException("库存事件绑定不一致");
    }
    /** 本地流水与快照生成同事务；事件时间不代表跨仓全局提交顺序。 */
    public static InventoryChangedEvent create(long tenant,Instant at,Data data) {
        Checks.positive(tenant); java.util.Objects.requireNonNull(data);
        return new InventoryChangedEvent(UUID.randomUUID().toString(),"trade.inventory-changed.v1",1,Long.toString(tenant),"INVENTORY_BALANCE",
            data.warehouseId()+":"+data.skuId(),data.balanceVersion(),at,"transaction-center",TraceContext.correlationUuid(),data);
    }
    private static void id(String value) {
        long parsed=Long.parseLong(value); Checks.positive(parsed);
        if(!Long.toString(parsed).equals(value)) throw new IllegalArgumentException("事件编号必须为规范正十进制");
    }
    private static void uuid(String value) {
        if(!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("事件关联编号必须为规范UUID");
    }
    /** 数量保持int64，流水号支持普通UUID和同一回库操作派生的OUT/IN。 */
    public record Data(String warehouseId,String skuId,String operationNo,long available,long reserved,long sold,long stockTotal,long balanceVersion) {
        /** 精确相加拒绝溢出，不能让伪造的负数回绕通过守恒校验。 */
        public Data {
            id(warehouseId); id(skuId); Checks.text(operationNo,128); Checks.positive(balanceVersion);
            if(available<0 || reserved<0 || sold<0 || stockTotal<0 || Math.addExact(Math.addExact(available,reserved),sold)!=stockTotal)
                throw new IllegalArgumentException("库存事件数量不守恒");
        }
    }
}
