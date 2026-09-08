package com.lrj.trade.jobs.model;
import java.time.Instant;
/** 单语句有界聚合的行映射，不携带业务键、租户或载荷。 */
public record OutboxQueueSnapshotRow(String status,long countLowerBound,Instant oldestAt,Instant observedAt) {}
