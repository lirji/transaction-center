package com.lrj.trade.inventory.model;

/** 库存流水只读视图；不向前端暴露操作主体的内部身份摘要。 */
public record LedgerEntry(String id,String operationRef,String warehouseId,String skuId,String operationType,
        long availableDelta,long reservedDelta,long soldDelta,long totalDelta,String reason,String occurredAt) {}
