package com.lrj.trade.core;
/** 只有受控事件重放可以重启REVIEW任务，普通入队始终保持终态。 */
public interface OutboxReplaySchedulePort {
    /** 调用方已锁事件；原子重启已隔离发布任务，返回单调累计领取次数供新轮基数使用。 */
    long requeueReviewed(long tenant,String eventId);
}
