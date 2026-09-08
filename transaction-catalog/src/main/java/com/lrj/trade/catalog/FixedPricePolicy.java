package com.lrj.trade.catalog;

import com.lrj.trade.catalog.model.QuoteItem;
import com.lrj.trade.catalog.model.Sku;
import com.lrj.trade.core.TradeException;
import org.springframework.stereotype.Component;

/** 固定价策略，整数溢出和前端安全整数边界必须在入库前拒绝。 */
@Component
public class FixedPricePolicy implements PricingPolicy {
    /** 固定版本不因重试或系统时间变化而变更。 */
    @Override
    public String version() { return "FIXED_PRICE_V1"; }
    /** 保留发布时的名称、规格、库存类型及金额，避免草稿污染交易快照。 */
    @Override
    public QuoteItem price(Sku sku,long quantity) {
        if(quantity<1 || quantity>1_000_000_000L) throw new TradeException("INVALID_ARGUMENT","购买数量超限");
        try {
            long amount=Math.multiplyExact(sku.unitPrice(),quantity);
            if(amount<0 || amount>9007199254740991L) throw new ArithmeticException();
            return new QuoteItem(sku.skuId(),sku.revision(),sku.title(),sku.specification(),sku.stockType(),
                quantity,sku.unitPrice(),amount);
        } catch(ArithmeticException invalid) {
            throw new TradeException("INVALID_ARGUMENT","报价金额超出安全整数范围");
        }
    }
}
