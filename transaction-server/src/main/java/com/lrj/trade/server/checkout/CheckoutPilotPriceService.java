package com.lrj.trade.server.checkout;

import com.lrj.trade.core.Actor;
import com.lrj.trade.core.TradeException;
import com.lrj.trade.server.checkout.CheckoutPilotQuoteMapper.FrozenPrice;
import com.lrj.trade.server.checkout.CheckoutPilotQuoteMapper.QuoteReference;
import com.lrj.trade.server.checkout.CheckoutPilotQuoteService.SourceEvidence;
import com.lrj.trade.server.checkout.CheckoutPilotQuoteService.SourceLine;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 在商城原价和营销签名均可核对时，冻结本次交易试点结算应付。 */
@Service
@ConditionalOnProperty(name = {"trade.checkout-pilot.enabled", "trade.checkout-pilot.freeze-enabled"}, havingValue = "true")
public class CheckoutPilotPriceService {
    private static final String CURRENCY = "CNY";
    private static final String PRICE_ERROR = "试点签价与原报价不符或已失效";
    private static final int MAX_ISSUER_CLOCK_SKEW_SECONDS = 5;
    private static final long MAX_NATIVE_QUOTE_MINOR = 9_007_199_254_740_991L;
    private final CheckoutPilotQuoteMapper mapper;
    private final CheckoutPilotQuoteService quotes;
    private final Clock clock;
    private final String keyId;
    private final PublicKey key;
    private final Map<String, String> marketingScope;

    /** 固定营销签名公钥及业务范围，缺任一个绑定字段即拒绝启用冻结。 */
    public CheckoutPilotPriceService(CheckoutPilotQuoteMapper mapper, CheckoutPilotQuoteService quotes, Clock clock,
            @Value("${trade.checkout-pilot.marketing-key-id}") String keyId,
            @Value("${trade.checkout-pilot.marketing-public-key}") String publicKey,
            @Value("${trade.checkout-pilot.marketing-tenant}") String tenant,
            @Value("${trade.checkout-pilot.marketing-organization}") String organization,
            @Value("${trade.checkout-pilot.marketing-shop}") String shop,
            @Value("${trade.checkout-pilot.marketing-campaign}") String campaign,
            @Value("${trade.checkout-pilot.marketing-funder}") String funder) {
        marketingScope = Map.of("marketingTenantId", tenant, "organizationId", organization, "shopId", shop,
            "campaignId", campaign, "funderId", funder);
        if (!keyId.matches("[A-Za-z0-9_-]{1,64}") || marketingScope.values().stream()
                .anyMatch(value -> !value.matches("[A-Za-z0-9_.:@/-]{1,64}"))) {
            throw new IllegalArgumentException("试点冻结需要指定营销公钥编号及完整业务范围");
        }
        this.mapper = mapper;
        this.quotes = quotes;
        this.clock = clock;
        this.keyId = keyId;
        this.key = CheckoutPriceProof.publicKey(publicKey);
    }

    /** 首份有效签价通过条件更新留存；相同签名可重放，其他签名不得覆盖。 */
    public Receipt freeze(Actor caller, String reference, String token) {
        quotes.requireCaller(caller);
        long tenant = caller.tenantId();
        var row = mapper.findByReference(tenant, reference)
            .orElseThrow(() -> new TradeException("NOT_FOUND", "试点报价引用不存在"));
        quotes.requireCurrentScope(row);
        var now = clock.instant();
        var claims = verify(token, tenant, row, now);
        var proofHash = CheckoutPriceProof.sha256(token);
        var existing = mapper.findFrozen(tenant, reference);
        if (existing.isPresent()) return receipt(existing.get(), proofHash);

        // 独立读商城后再写本地，条件更新决定并发胜出者，避免外部请求占用数据库锁。
        var source = quotes.revalidate(row);
        var frozen = price(row, source, claims, proofHash, token).frozen();
        if (mapper.freeze(tenant, frozen) == 1) return receipt(frozen, proofHash);
        return receipt(mapper.findFrozen(tenant, reference).orElseThrow(CheckoutPilotPriceService::conflict), proofHash);
    }

    private Map<String, String> verify(String token, long tenant, QuoteReference row, Instant now) {
        try {
            var claims = CheckoutPriceProof.verify(token, keyId, key);
            var issuedAt = Instant.parse(claims.get("issuedAt"));
            var expiresAt = Instant.parse(claims.get("expiresAt"));
            if (!claims.entrySet().containsAll(marketingScope.entrySet())
                    || !row.quoteRef().equals(claims.get("tradeQuoteRef"))
                    || !row.scopeVersion().equals(claims.get("scopeVersion"))
                    || !row.ownerRef().equals(claims.get("ownerRef"))
                    || !Long.toString(tenant).equals(claims.get("tradeTenantId"))
                    || !CURRENCY.equals(claims.get("currency")) || !now.isBefore(expiresAt)
                    || expiresAt.isAfter(row.expiresAt()) || !issuedAt.isBefore(expiresAt)
                    || issuedAt.isAfter(now.plusSeconds(MAX_ISSUER_CLOCK_SKEW_SECONDS))) {
                throw conflict();
            }
            return claims;
        } catch (IllegalArgumentException | DateTimeParseException invalid) {
            throw conflict();
        }
    }

    /** 创建原生报价前按真实商城与当前信任配置重验已冻结的逐行分值。 */
    List<PricedLine> checkoutLines(long tenant, QuoteReference row, SourceEvidence source, FrozenPrice frozen) {
        if (!CheckoutPriceProof.sha256(frozen.proof()).equals(frozen.proofHash())) throw conflict();
        var claimed = verify(frozen.proof(), tenant, row, clock.instant());
        var checked = price(row, source, claimed, frozen.proofHash(), frozen.proof());
        if (!frozen.equals(checked.frozen())) throw conflict();
        return checked.lines();
    }

    private CheckedPrice price(QuoteReference row, SourceEvidence source, Map<String, String> claims,
            String proofHash, String token) {
        var lines = source.items().stream().sorted(Comparator.comparing(SourceLine::skuId)).toList();
        if (!quotes.digest(source).equals(claims.get("sourceDigest"))
                || !source.eligibleSkuId().equals(claims.get("eligibleSkuId"))
                || !cartDigest(lines, claims.get("shopId")).equals(claims.get("cartDigest"))) {
            throw conflict();
        }
        String[] pricedLines = claims.get("lines").split(",", -1);
        if (pricedLines.length != lines.size()) throw conflict();
        long gross = 0;
        long payable = 0;
        List<PricedLine> result = new ArrayList<>(lines.size());
        try {
            for (int index = 0; index < lines.size(); index++) {
                var line = lines.get(index);
                String[] priced = pricedLines[index].split("~", -1);
                if (priced.length != 5 || !line.skuId().equals(priced[0]) || !line.skuId().equals(priced[1])
                        || !Integer.toString(line.quantity()).equals(priced[2]) || !line.unitPriceMinor().equals(priced[3])) {
                    throw conflict();
                }
                long unitPrice = minor(line.unitPriceMinor());
                long original = Math.multiplyExact(line.quantity(), unitPrice);
                long net = minor(priced[4]);
                if (net > original || (!source.eligibleSkuId().equals(line.skuId()) && net != original)) throw conflict();
                gross = Math.addExact(gross, original);
                payable = Math.addExact(payable, net);
                result.add(new PricedLine(line.skuId(), line.skuRevision(), line.quantity(), unitPrice, net));
            }
            long discount = gross - payable;
            // 原生交易金额需能无损传输，本试点仅验收需要支付的订单。
            if (payable <= 0 || discount <= 0 || gross > MAX_NATIVE_QUOTE_MINOR || gross != minor(claims.get("grossMinor"))
                    || payable != minor(claims.get("payableMinor")) || discount != minor(claims.get("discountMinor"))) {
                throw conflict();
            }
            var expiresAt = Instant.parse(claims.get("expiresAt")).truncatedTo(ChronoUnit.MILLIS);
            return new CheckedPrice(new FrozenPrice(row.quoteRef(), proofHash, token, gross, discount, payable, expiresAt),
                List.copyOf(result));
        } catch (ArithmeticException | IllegalArgumentException invalid) {
            throw conflict();
        }
    }

    private static long minor(String value) {
        if (!value.matches("0|[1-9][0-9]{0,18}")) throw conflict();
        return Long.parseLong(value);
    }

    private static String cartDigest(List<SourceLine> lines, String shop) {
        // 试点以 SKU 作为购物篮行键，类目、品牌、底价和运费使用与营销约定的空值。
        var canonical = new StringBuilder(CURRENCY).append('\n');
        for (var line : lines) {
            canonical.append(line.skuId()).append('|').append(line.skuId()).append('|').append(shop)
                .append("|||").append(line.quantity()).append('|').append(line.unitPriceMinor()).append("|0|false\n");
        }
        return "sha256:" + CheckoutPriceProof.sha256(canonical.toString());
    }

    private Receipt receipt(FrozenPrice row, String proofHash) {
        if (!row.proofHash().equals(proofHash) || !clock.instant().isBefore(row.expiresAt())) throw conflict();
        return new Receipt(row.quoteRef(), CURRENCY, Long.toString(row.grossMinor()), Long.toString(row.discountMinor()),
            Long.toString(row.payableMinor()), row.expiresAt());
    }

    private static TradeException conflict() {
        return new TradeException(CheckoutPilotQuoteService.CONFLICT, PRICE_ERROR);
    }

    /** 已和原始报价逐字段核对的商品行，可按明确配对生成原生规格报价。 */
    record PricedLine(String commerceSkuId, long commerceRevision, int quantity, long unitPrice, long payable) { }

    /** 同一轮验签同时得到冻结金额和原生结算需要的逐行分值。 */
    private record CheckedPrice(FrozenPrice frozen, List<PricedLine> lines) { }

    /** 冻结回执只提供结算金额及原引用，敏感归属和原始签名留在服务端。 */
    public record Receipt(String tradeQuoteRef, String currency, String grossMinor, String discountMinor,
                          String payableMinor, Instant expiresAt) { }
}
