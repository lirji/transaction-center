package com.lrj.trade.core;
/** 操作与差异当前关联；Worker不持资金域锁，只核自己对应的版本与操作号。 */
public record ReconciliationCaseGuard(String caseNo,long channelId,String referenceType,String businessRef,String status,long version,String resolutionRef) {}
