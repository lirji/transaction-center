package com.lrj.trade.inventory.model;

/** 库存余额只读视图，所有ID均使用字符串避免前端精度丢失。 */
public record Balance(String id,String warehouseId,String skuId,long stockTotal,long available,long reserved,long sold,long version) {}
