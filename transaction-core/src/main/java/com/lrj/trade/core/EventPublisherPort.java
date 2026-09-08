package com.lrj.trade.core;

/** 网络发布边界，调用方必须没有数据库事务；返回仅表示Broker确认，不等于本地完成。 */
public interface EventPublisherPort {
    /** 默认关闭时不能领取发布任务；开启后的每次调用必须在20秒以内返回或抛错。 */
    boolean enabled();
    /** 发送已经验证的不可变载荷，失败可能已经被Broker接收，重试保留原eventId。 */
    Receipt publish(OutboxEventCatalog.Message message);
    /** 持久化Broker位置用于核对，不能由业务调用方伪造发送成功。 */
    record Receipt(String eventId,String topic,int partition,long offset) {
        /** 明确拒绝不完整ACK，避免将无有效位置的事件记为SENT。 */
        public Receipt {
            Checks.text(eventId,36); Checks.text(topic,249);
            if(partition<0 || offset<0) throw new IllegalArgumentException("消息确认位置无效");
        }
    }
}
