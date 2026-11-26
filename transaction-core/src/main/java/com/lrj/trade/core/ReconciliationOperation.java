package com.lrj.trade.core;
import java.time.Instant;
/** 永久操作与可变执行状态同存；敏感请求键只在内部使用。 */
public record ReconciliationOperation(long id,String operationNo,String requestHash,String caseNo,String action,Long channelId,String referenceType,String businessRef,Long caseVersion,Instant acceptedAt,String status,String lastCode,long version,String businessNo) {
    /** 默认日志只给状态，不展开业务引用。 */
    @Override public String toString() {return "ReconciliationOperation[status="+status+"]";}
}
