package com.lrj.trade.core;
import java.time.Instant;
/** 后台发布摘要不暴露完整payload，只保留定位、失败和已确认位置。 */
public record OutboxPublication(String id,String eventId,String eventType,String aggregateId,String status,long version,
    long attemptBase,long replayCount,long failureCount,String lastError,Instant firstFailureAt,Instant lastFailureAt,
    Instant publishedAt,String publishedTopic,Integer publishedPartition,Long publishedOffset) {}
