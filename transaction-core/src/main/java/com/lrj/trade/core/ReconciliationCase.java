package com.lrj.trade.core;
import java.time.*;
/** 差异持久视图，证据引用仅供可信财务单项读取，后台摘要另行投影。 */
public record ReconciliationCase(String id,String caseNo,String sourceId,long channelId,LocalDate statementDate,
 String externalLineKey,long revision,String differenceType,String referenceType,String businessRef,
 String evidenceHash,String evidenceRef,String priorCaseNo,String contentHash,String status,long version,Instant createdAt,String businessNo) {}
