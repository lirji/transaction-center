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

    /** 按引用加锁串行化原生订单创建，外部商城核验在持锁前完成。 */
    Optional<QuoteReference> lockByReference(@Param("tenant") long tenant, @Param("reference") String reference);

    /** 固化首个原生订单关联以及买家命令摘要，与下单和库存事务一起提交。 */
    int bindOrder(@Param("tenant") long tenant, @Param("binding") NativeBinding binding);

    /** 从交易自己的订单复核当前可支付状态及关联证据，不外传原始签名和买家字段。 */
    Optional<BoundOrder> findBoundOrder(@Param("tenant") long tenant, @Param("reference") String reference);
    /** 只返回这笔交易实际冻结的商城关联，不展开买家、价格凭据或签名。 */
    Optional<CheckoutPilotAuditLookup.Binding> findAuditBinding(@Param("tenant") long tenant, @Param("orderId") long orderId);

    /** 仅首份有效预算回执可以开放试点订单支付。 */
    int acceptFunding(@Param("tenant") long tenant, @Param("funding") FundingBinding funding);

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

    /** 命令摘要和首份凭证关联都留存，重试无需重新占库存。 */
    record NativeBinding(String quoteRef, long orderId, String requestHash, String proofHash, Instant boundAt) { }

    /** 受控查询所需的报价与原生订单事实。 */
    record BoundOrder(String orderId, String orderNo, String nativeQuoteId, String requestHash, String scopeVersion,
                      String storeId, String proofHash, String currency, String grossMinor, String discountMinor,
                      String payableMinor, String orderStatus, Instant orderExpiresAt, String financialStatus,
                      Instant paidAt, String fundingReceiptHash, String businessNo) { }

    /** 已核验预算回执只写入与原生订单同一行的交易证据。 */
    record FundingBinding(String reference, long orderId, String applicationId, String receiptHash, Instant validUntil) { }
}
