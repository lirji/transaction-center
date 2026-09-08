package com.lrj.trade.core;
import java.time.Instant;
import java.util.List;
/** 对账只读资金事实；扫描结束不代表渠道账单或跨页快照已经完整。 */
public interface ReconciliationFactsPort {
    /** 上界为空开启新轮；后续页保留上界，空结果仍须按hasMore继续。 */
    Page scan(long tenant,long channelId,long after,Long upper,int scanLimit);
    /** 原始账本标识与实际收退款时间，不导出凭据、用户隐私或原始回调。 */
    record Fact(String ledgerId,String ledgerNo,String direction,long amount,String currency,
        String receiptNo,String refundNo,String orderId,String disposition,String providerTransactionNo,
        String providerRefundNo,Instant providerOccurredAt,Instant confirmedAt) {}
    /** 游标跟随已扫描候选而非匹配结果；ID字符串避免JSON整数精度丢失。 */
    record Page(String coverage,String channelId,String upper,String nextCursor,boolean hasMore,
        int scannedCount,Instant observedAt,List<Fact> facts) {
        /** 防止调用者改变一次查询产生的事实集合。 */
        public Page { facts=List.copyOf(facts); }
    }
}
