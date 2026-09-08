package com.lrj.trade.core;
/** 当前锁读的租户维护状态，due由数据库时间计算，不能依赖进程时钟。 */
public record OutboxMaintenanceState(long cursor,long upperId,boolean active,long scanned,long candidates,boolean due) {}
