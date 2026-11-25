package com.lrj.trade.catalog.model;

import java.time.Instant;
import java.util.List;

/** 已验签的试点金额与原生规格配对，仅由受信结算服务提交给目录。 */
public record CheckoutPrice(String currency, long payableAmount, Instant expiresAt, List<Line> lines) {
    public static final String POLICY = "checkout-pilot/v1";

    /** 冻结行金额按已发布原生规格核对，不允许调用端覆盖商品原价。 */
    public record Line(long skuId, int quantity, long unitPrice, long payableAmount) { }
}
