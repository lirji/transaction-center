package com.lrj.trade.server.checkout;

import java.util.Optional;
import org.springframework.stereotype.Service;

/** 只读投影将已冻结的商城来源提供给交易审计，读历史无需重新向商城核验正在售卖的商品。 */
@Service
public class CheckoutPilotAuditLookup {
    private final CheckoutPilotQuoteMapper mapper;

    /** 来源只从交易数据库的订单绑定取得。 */
    public CheckoutPilotAuditLookup(CheckoutPilotQuoteMapper mapper) {
        this.mapper=mapper;
    }

    /** 输入由交易侧已授权的原生订单提供。 */
    public Optional<Binding> find(long tenant,long orderId) {
        return mapper.findAuditBinding(tenant,orderId);
    }

    /** 上游授权交叉核验所需的租户、店铺及原可信引用。 */
    public record Binding(String commerceTenant,String merchantId,String storeId,String commerceQuoteId,
                          String scopeVersion,String checkoutReference,String fundingApplicationId) { }
}
