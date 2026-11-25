package com.lrj.trade.server.checkout;

import java.time.Instant;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 商城报价每个交易租户只分配一次试点报价引用。 */
@Mapper
public interface CheckoutPilotQuoteMapper {
    /** 并发重复调用保留首份证据与引用。 */
    int insert(@Param("tenant") long tenant, @Param("quote") QuoteReference quote);

    /** 只按已授权的原生租户和原始商城报价定位。 */
    Optional<QuoteReference> find(@Param("tenant") long tenant, @Param("commerceQuoteId") String commerceQuoteId);

    /** 并发插入后在当前事务内取得首份已持久化证据。 */
    Optional<QuoteReference> lock(@Param("tenant") long tenant, @Param("commerceQuoteId") String commerceQuoteId);

    /** 价格冻结阶段使用交易服务生成的引用读取原始商城证据。 */
    Optional<QuoteReference> findByReference(@Param("tenant") long tenant, @Param("reference") String reference);

    /** 一次写入且验证原报价未过期；竞争失败后读取首份有效回执。 */
    int freeze(@Param("tenant") long tenant, @Param("price") FrozenPrice price);

    /** 按租户和引用恢复已冻结价格。 */
    Optional<FrozenPrice> findFrozen(@Param("tenant") long tenant, @Param("reference") String reference);

    /** 证据为交易服务验收后的持久快照，对外只返回必要引用。 */
    record QuoteReference(String quoteRef, String commerceQuoteId, String ownerRef,
                          String scopeVersion, String storeId, String evidenceJson, Instant expiresAt, Instant createdAt) { }

    /** 冻结价格和验签材料供原生订单的后续阶段追溯。 */
    record FrozenPrice(String quoteRef, String proofHash, String proof, long grossMinor, long discountMinor,
                       long payableMinor, Instant expiresAt) { }
}
