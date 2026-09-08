package com.lrj.trade.jobs.model;
import java.time.Instant;
/** 内部当前任务摘要，原始业务键在出域之前脱敏。 */
public record PublicationTask(String id,String businessKey,String status,long attempts,Instant nextRunAt,String lastError) {}
