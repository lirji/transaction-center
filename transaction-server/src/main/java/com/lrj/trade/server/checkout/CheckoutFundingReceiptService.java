package com.lrj.trade.server.checkout;

import com.lrj.trade.core.Actor;
import com.lrj.trade.core.TradeException;
import com.lrj.trade.order.OrderPaymentPort;
import com.lrj.trade.server.checkout.CheckoutPilotQuoteMapper.FundingBinding;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 信任独立资金签名，精确匹配首份回执和本地订单后开放支付。 */
@Service
@ConditionalOnProperty(name = {"trade.checkout-pilot.enabled", "trade.checkout-pilot.funding-enabled"}, havingValue = "true")
public class CheckoutFundingReceiptService {
    static final int MAX_RECEIPT = 4096;
    private static final String PURPOSE = "marketing.checkout-funding/v1";
    private static final Set<String> FIELDS = Set.of("purpose", "marketingTenantId", "tradeTenantId", "tradeQuoteRef",
        "orderId", "scopeVersion", "storeId", "priceProofHash", "currency", "discountMinor", "payableMinor",
        "applicationId", "expiresAt");
    private final CheckoutPilotQuoteService quotes;
    private final CheckoutPilotQuoteMapper mapper;
    private final OrderPaymentPort orders;
    private final Clock clock;
    private final String tenant;
    private final String keyId;
    private final PublicKey key;

    /** 资金公钥独立于签价信任域，配置缺失时保持支付门禁。 */
    public CheckoutFundingReceiptService(CheckoutPilotQuoteService quotes, CheckoutPilotQuoteMapper mapper,
            OrderPaymentPort orders, Clock clock, @Value("${trade.checkout-pilot.marketing-tenant}") String tenant,
            @Value("${trade.checkout-pilot.marketing-key-id}") String pricingKeyId,
            @Value("${trade.checkout-pilot.marketing-public-key}") String pricingPublicKey,
            @Value("${trade.checkout-pilot.funding-key-id}") String keyId,
            @Value("${trade.checkout-pilot.funding-public-key}") String publicKey) {
        if (!keyId.matches("[A-Za-z0-9_-]{1,64}") || !tenant.matches("[A-Za-z0-9_.:@/-]{1,64}")
                || keyId.equals(pricingKeyId) || pricingPublicKey.isBlank()) {
            throw new IllegalArgumentException("试点预算回执需要独立公钥和营销租户");
        }
        this.key = CheckoutPriceProof.publicKey(publicKey);
        if (key.equals(CheckoutPriceProof.publicKey(pricingPublicKey))) {
            throw new IllegalArgumentException("试点签价与预算回执必须使用不同公钥");
        }
        this.quotes = quotes;
        this.mapper = mapper;
        this.orders = orders;
        this.clock = clock;
        this.tenant = tenant;
        this.keyId = keyId;
    }

    /** 与支付和取消共用订单行锁，首次回执生效前要求订单仍待支付。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Accepted accept(Actor caller, String reference, String token) {
        quotes.requireCaller(caller);
        try {
            var claims = CheckoutPriceProof.verify(token, keyId, key, PURPOSE, FIELDS);
            var bound = mapper.findBoundOrder(caller.tenantId(), reference)
                .orElseThrow(() -> new TradeException("NOT_FOUND", "试点报价尚未关联订单"));
            String application = claims.get("applicationId");
            Instant until = Instant.parse(claims.get("expiresAt"));
            if (!reference.equals(claims.get("tradeQuoteRef")) || !application.matches(CheckoutPilotQuoteService.QUOTE_ID_PATTERN)
                    || !tenant.equals(claims.get("marketingTenantId"))
                    || !Long.toString(caller.tenantId()).equals(claims.get("tradeTenantId"))
                    || !bound.orderId().equals(claims.get("orderId")) || !bound.scopeVersion().equals(claims.get("scopeVersion"))
                    || !bound.storeId().equals(claims.get("storeId")) || !bound.proofHash().equals(claims.get("priceProofHash"))
                    || !bound.currency().equals(claims.get("currency")) || !bound.discountMinor().equals(claims.get("discountMinor"))
                    || !bound.payableMinor().equals(claims.get("payableMinor")) || !bound.orderExpiresAt().equals(until)) {
                throw invalid();
            }
            String hash = CheckoutPriceProof.sha256(token);
            if (hash.equals(bound.fundingReceiptHash())) return new Accepted(reference, application, until);
            if (bound.fundingReceiptHash() != null) throw invalid();
            long orderId = Long.parseLong(bound.orderId());
            var order = orders.lockPaymentOrder(caller.tenantId(), orderId);
            var current = mapper.findBoundOrder(caller.tenantId(), reference).orElseThrow(CheckoutFundingReceiptService::invalid);
            if (hash.equals(current.fundingReceiptHash())) return new Accepted(reference, application, until);
            if (current.fundingReceiptHash() != null) throw invalid();
            if (!"PENDING_PAYMENT".equals(order.status()) || !"UNPAID".equals(order.financialStatus())
                    || !clock.instant().isBefore(until)) throw invalid();
            int changed = mapper.acceptFunding(caller.tenantId(), new FundingBinding(reference, orderId, application, hash, until));
            if (changed != 1) throw invalid();
            return new Accepted(reference, application, until);
        } catch (IllegalArgumentException | DateTimeParseException malformed) {
            throw invalid();
        }
    }

    private static TradeException invalid() {
        return new TradeException(CheckoutPilotQuoteService.CONFLICT, "预算回执无效、与原订单不一致或已经失效");
    }

    /** 应答只包含关联编号和有效期，原始签名不回显。 */
    public record Accepted(String tradeQuoteRef, String applicationId, Instant expiresAt) { }
}
