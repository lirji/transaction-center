package com.lrj.trade.inventory.model;

/** 锁定后的预占事实，不提供跨模块持久对象。 */
public record Reservation(String id,long warehouse,long sku,long quantity,String status) {}
