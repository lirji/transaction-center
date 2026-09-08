package com.lrj.trade.core;
import java.time.Instant;
/** 永久来源回执保存首次结果，后续case状态变更不影响重放返回。 */
public record ReconciliationReportReceipt(long id,String requestHash,String caseNo,Instant acceptedAt) {}
