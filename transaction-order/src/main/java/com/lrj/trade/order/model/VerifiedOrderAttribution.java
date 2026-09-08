package com.lrj.trade.order.model;

import com.lrj.trade.core.Actor;
import com.lrj.trade.core.TradeException;
import java.time.Duration;
import java.time.Instant;

/** 验签后的最小归因事实；提交时再次校验时间与绑定，不能作为奖励已达标证明。 */
public record VerifiedOrderAttribution(String issuer,String jti,String tenantId,String actorKey,String memberId,
    String quoteId,String referralRef,String campaignId,long definitionVersion,String tokenHash,
    Instant issuedAt,Instant expiresAt,Instant notBefore) {
    /** 锁等待或加密后再检查完整授权窗口，禁止使用验签时刻代替实际插单时刻。 */
    public void requireUsable(Actor actor,long member,long quote,Instant now) {
        if(!Long.toString(actor.tenantId()).equals(tenantId) || !actor.key().equals(actorKey)
            || !Long.toString(member).equals(memberId) || !Long.toString(quote).equals(quoteId)
            || issuedAt==null || expiresAt==null || issuedAt.isAfter(now) || !expiresAt.isAfter(issuedAt)
            || !now.isBefore(expiresAt) || (notBefore!=null && notBefore.isAfter(now))
            || Duration.between(issuedAt,expiresAt).compareTo(Duration.ofMinutes(15))>0)
            throw new TradeException("ORDER_ATTRIBUTION_UNTRUSTED","订单归因令牌无效");
    }
    /** 默认日志不输出主体和活动关联信息。 */
    @Override public String toString() { return "VerifiedOrderAttribution[REDACTED]"; }
}
