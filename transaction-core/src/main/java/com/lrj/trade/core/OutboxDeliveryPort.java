package com.lrj.trade.core;

/** 发布工作者只访问Outbox领域Port，租约与本地发布状态由调用方原子编排。 */
public interface OutboxDeliveryPort {
    /** 当前锁读持久化事件，返回空仅表示不存在；必须在调用方事务中执行。 */
    Row lock(long tenant,String eventId);
    /** Broker确认后记录位置；失租时调用方必须回滚本次变更。 */
    void sent(long tenant,String eventId,EventPublisherPort.Receipt receipt);
    /** 分类化失败信息不含异常原文，绝不覆盖已SENT状态。 */
    void failed(long tenant,String eventId,String code,boolean review);
    /** 载荷为数据库中已保存的字符串，不从当前业务域重建。 */
    record Row(long id,long tenant,String eventId,String eventType,String aggregateId,String payload,String status,long version,long attemptBase,long replayCount) {}
}
