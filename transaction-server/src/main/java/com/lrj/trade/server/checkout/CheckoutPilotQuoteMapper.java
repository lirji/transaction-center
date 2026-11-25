package com.lrj.trade.server.checkout;

import java.time.Instant;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 商城报价每个交易租户只分配一次试点报价引用。 */
@Mapper
public interface CheckoutPilotQuoteMapper {
    /** 并发重复调用保留首份证据与引用。 */
    int insert(@Param("quote") QuoteReference quote);

    /** 只按已授权的原生租户和原始商城报价定位。 */
    Optional<QuoteReference> find(@Param("tenantId") long tenantId, @Param("commerceQuoteId") String commerceQuoteId,
                                 @Param("lock") boolean lock);

    /** 证据为交易服务验收后的持久快照，对外只返回必要引用。 */
    record QuoteReference(String quoteRef, long tenantId, String commerceQuoteId, String ownerRef,
                          String scopeVersion, String storeId, String evidenceJson, Instant expiresAt, Instant createdAt) { }
}
