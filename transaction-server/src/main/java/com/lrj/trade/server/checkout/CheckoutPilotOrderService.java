package com.lrj.trade.server.checkout;

import com.lrj.trade.catalog.model.CheckoutPrice;
import com.lrj.trade.core.Actor;
import com.lrj.trade.core.Checks;
import com.lrj.trade.core.Commands;
import com.lrj.trade.core.TradeException;
import com.lrj.trade.member.MemberTradingPort;
import com.lrj.trade.order.OrderService;
import com.lrj.trade.order.model.OrderView;
import com.lrj.trade.order.model.ShippingAddress;
import com.lrj.trade.server.checkout.CheckoutPilotQuoteMapper.NativeBinding;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 将已核验商城报价变成属于同一买家的唯一原生交易订单。 */
@Service
@ConditionalOnProperty(name = {"trade.checkout-pilot.enabled", "trade.checkout-pilot.freeze-enabled",
    "trade.checkout-pilot.order-enabled"}, havingValue = "true")
public class CheckoutPilotOrderService {
    private static final String OPERATION = "checkout-pilot.order";
    private static final String OWNER_SCOPE = "commerce-checkout-owner-v1";
    private static final String IDENTIFIER = "[A-Za-z0-9_.:@/-]{1,64}";
    private static final int MAX_BUYERS = 1000;
    private static final int MAX_SKUS = 50;
    private final CheckoutPilotQuoteMapper mapper;
    private final CheckoutPilotQuoteService quotes;
    private final CheckoutPilotPriceService prices;
    private final MemberTradingPort members;
    private final OrderService orders;
    private final Commands commands;
    private final ObjectMapper json;
    private final Clock clock;
    private final Map<Long, String> buyerLinks;
    private final Map<String, SkuLink> skuLinks;
    private final String commerceTenant;
    private final String nativeMerchant;

    /** 仅启用审批确认的精确配对，拒绝模糊标识、空表或重复关联。 */
    public CheckoutPilotOrderService(CheckoutPilotQuoteMapper mapper, CheckoutPilotQuoteService quotes,
            CheckoutPilotPriceService prices, MemberTradingPort members, OrderService orders, Commands commands,
            ObjectMapper json, Clock clock,
            @Value("${trade.checkout-pilot.commerce-tenant}") String commerceTenant,
            @Value("${trade.checkout-pilot.sku-id}") String eligibleSku,
            @Value("${trade.checkout-pilot.native-merchant-no}") String nativeMerchant,
            @Value("${trade.checkout-pilot.member-links}") String buyers,
            @Value("${trade.checkout-pilot.sku-links}") String skus) {
        this.mapper = mapper;
        this.quotes = quotes;
        this.prices = prices;
        this.members = members;
        this.orders = orders;
        this.commands = commands;
        this.json = json;
        this.clock = clock;
        this.commerceTenant = commerceTenant;
        this.nativeMerchant = nativeMerchant;
        buyerLinks = json.readValue(buyers, new TypeReference<Map<Long, String>>() { });
        skuLinks = json.readValue(skus, new TypeReference<Map<String, SkuLink>>() { });
        if (!nativeMerchant.matches(IDENTIFIER) || buyerLinks.isEmpty() || buyerLinks.size() > MAX_BUYERS
                || skuLinks.isEmpty() || skuLinks.size() > MAX_SKUS || !skuLinks.containsKey(eligibleSku)
                || buyerLinks.entrySet().stream().anyMatch(e -> e.getKey() <= 0
                    || e.getValue() == null || !e.getValue().matches(IDENTIFIER))
                || skuLinks.entrySet().stream().anyMatch(e -> !e.getKey().matches(CheckoutPilotQuoteService.ID_PATTERN)
                    || e.getValue() == null || !e.getValue().valid())
                || new HashSet<>(buyerLinks.values()).size() != buyerLinks.size()
                || skuLinks.values().stream().map(SkuLink::tradeSkuId).distinct().count() != skuLinks.size()) {
            throw new IllegalArgumentException("原生试点下单需要已确认的收款商户及一对一买家和规格配对");
        }
    }

    /** 同一报价只创建一单，库存、原生报价、订单与试点关联全部提交或全部回滚。 */
    public OrderView create(Actor actor, String key, String reference, Long warehouse, ShippingAddress address) {
        quotes.requireBuyerTenant(actor);
        Checks.text(key, 128);
        if (warehouse != null) Checks.positive(warehouse);
        long tenant = actor.tenantId();
        var hash = Checks.hash(actor.key(), reference, warehouse, address == null ? null : address.recipient(),
            address == null ? null : address.phone(), address == null ? null : address.address());
        String completed = commands.completed(actor, OPERATION, key, hash);
        if (completed != null) return orders.read(actor, completed);
        var row = mapper.findByReference(tenant, reference)
            .orElseThrow(() -> new TradeException("NOT_FOUND", "试点报价引用不存在"));
        var member = buyerLinks.get(members.memberId(actor));
        String owner = member == null ? "" : CheckoutPriceProof.sha256(
            json.writeValueAsString(List.of(OWNER_SCOPE, commerceTenant, member, row.commerceQuoteId())));
        if (!row.ownerRef().equals(owner)) {
            throw new TradeException(CheckoutPilotQuoteService.UNTRUSTED, "商城报价未绑定到本交易买家");
        }
        var bound = mapper.findBoundOrder(tenant, reference);
        if (bound.isPresent()) return orders.read(actor, replay(bound.get(), hash));
        var frozen = mapper.findFrozen(tenant, reference).orElseThrow(CheckoutPilotOrderService::invalid);
        var source = quotes.revalidate(row);
        var lines = prices.checkoutLines(tenant, row, source, frozen).stream().map(line -> {
            var sku = skuLinks.get(line.commerceSkuId());
            if (sku == null || sku.commerceRevision() != line.commerceRevision()) throw invalid();
            return new CheckoutPrice.Line(sku.tradeSkuId(), sku.tradeRevision(), line.quantity(), line.unitPrice(), line.payable());
        }).toList();
        var price = new CheckoutPrice(source.currency(), frozen.payableMinor(), frozen.expiresAt(), lines);

        String number = commands.run(actor, OPERATION, key, hash, () -> {
            var locked = mapper.lockByReference(tenant, reference).orElseThrow(CheckoutPilotOrderService::invalid);
            var prior = mapper.findBoundOrder(tenant, reference);
            if (prior.isPresent()) return replay(prior.get(), hash);
            quotes.requireCurrentScope(locked);
            var order = orders.createCheckout(actor, price, warehouse, address, nativeMerchant);
            var binding = new NativeBinding(reference, Long.parseLong(order.orderId()), hash, frozen.proofHash(),
                clock.instant().truncatedTo(ChronoUnit.MILLIS));
            if (mapper.bindOrder(tenant, binding) != 1) throw invalid();
            return order.orderNo();
        });
        return orders.read(actor, number);
    }

    private static String replay(CheckoutPilotQuoteMapper.BoundOrder bound, String hash) {
        if (!bound.requestHash().equals(hash)) throw invalid();
        return bound.orderNo();
    }

    private static TradeException invalid() {
        return new TradeException(CheckoutPilotQuoteService.CONFLICT, "试点报价未冻结、已关联其他下单请求或配对失效");
    }

    /** 同价的重新发布也须按新版本重新确认配对，防止规格语义变更后沿用旧授权。 */
    private record SkuLink(long commerceRevision, long tradeSkuId, long tradeRevision) {
        boolean valid() {
            return commerceRevision > 0 && tradeSkuId > 0 && tradeRevision > 0;
        }
    }

}
