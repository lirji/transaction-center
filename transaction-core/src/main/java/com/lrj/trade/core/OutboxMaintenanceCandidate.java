package com.lrj.trade.core;
/** 历史分页仅取定位与筛选字段，不加载完整消息载荷。 */
public record OutboxMaintenanceCandidate(long id,String eventId,String eventType,String status) {}
