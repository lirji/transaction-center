package com.lrj.trade.core;

import java.time.Instant;
/** 所有可发布V1事件的只读公共头；业务数据仍由各自强类型合同验证。 */
public interface V1EventEnvelope {
    /** 永久事件键用于跨重试去重。 */
    String eventId();
    /** 持久关联UUID用于传输追踪，不允许发布时改写。 */
    String traceId();
    /** 类型必须匹配固定发布白名单。 */
    String eventType();
    /** 租户取自已验证的载荷，须再与持久化索引核对。 */
    String tenantId();
    /** 聚合类型与ID共同决定分区键。 */
    String aggregateType();
    /** 聚合编号不能从当前业务状态重新计算。 */
    String aggregateId();
    /** 消费者按聚合版本处理乱序。 */
    long aggregateVersion();
    /** 事件事实时间，不是发送时间。 */
    Instant occurredAt();
}
