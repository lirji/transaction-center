package com.lrj.trade.server.checkout;

import com.lrj.trade.core.Actor;
import com.lrj.trade.core.TradeException;
import com.lrj.trade.server.checkout.CheckoutPilotQuoteMapper.QuoteReference;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/** 从指定商城获取受信报价事实，分配可重放的交易试点报价引用。 */
@Service
@ConditionalOnProperty(name="trade.checkout-pilot.enabled", havingValue="true")
public class CheckoutPilotQuoteService {
    public static final String UNTRUSTED = "CHECKOUT_PILOT_UNTRUSTED";
    public static final String CONFLICT = "CHECKOUT_PILOT_SOURCE_CONFLICT";
    public static final String UNAVAILABLE = "CHECKOUT_PILOT_SOURCE_UNAVAILABLE";
    static final String QUOTE_ID_PATTERN = "[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}";
    static final String DIGEST_PATTERN = "[0-9a-f]{64}";
    private static final String ID_PATTERN = "[A-Za-z0-9_.:-]{1,64}";
    private static final String SOURCE_PATH = "/internal/v1/checkout-pilot/quotes/{quoteId}";
    private static final int MAX_DISTINCT_ITEMS = 50;
    private final CheckoutPilotQuoteMapper mapper;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final Clock clock;
    private final RestClient commerce;
    private final Scope scope;

    /** 装配单一试点范围及商城连接，缺少可信配置时在启动阶段拒绝启用。 */
    public CheckoutPilotQuoteService(CheckoutPilotQuoteMapper mapper, PlatformTransactionManager manager,
            ObjectMapper json, Clock clock,
            @Value("${trade.checkout-pilot.commerce-base-url}") URI url,
            @Value("${trade.checkout-pilot.commerce-token}") String token,
            @Value("${trade.checkout-pilot.tenant-id}") long tenantId,
            @Value("${trade.checkout-pilot.caller-key}") String callerKey,
            @Value("${trade.checkout-pilot.scope-version}") String scopeVersion,
            @Value("${trade.checkout-pilot.commerce-tenant}") String commerceTenant,
            @Value("${trade.checkout-pilot.merchant-id}") String merchant,
            @Value("${trade.checkout-pilot.store-id}") String store,
            @Value("${trade.checkout-pilot.sku-id}") String sku) {
        if (tenantId <= 0 || !callerKey.matches(DIGEST_PATTERN) || !token.matches("[A-Za-z0-9_-]{43,128}")
                || !List.of(scopeVersion, commerceTenant, merchant, store, sku).stream().allMatch(CheckoutPilotQuoteService::identifier)
                || !validOrigin(url)) {
            throw new IllegalArgumentException("试点报价配置需要固定租户、商城地址、授权主体及范围");
        }
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
        this.json = json;
        this.clock = clock;
        this.scope = new Scope(tenantId, callerKey, scopeVersion, commerceTenant, merchant, store, sku);
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.commerce = RestClient.builder().baseUrl(url.toString()).requestFactory(factory)
            .defaultHeaders(headers -> {
                headers.setBearerAuth(token);
                headers.set("X-Trade-Tenant", Long.toString(tenantId));
            }).build();
    }

    /** 先从主库恢复已有回执，首次外部核验在本地写事务之外执行。 */
    public Prepared prepare(Actor caller, String commerceQuoteId, String ownerRef) {
        if (caller.tenantId() != scope.tenantId() || !scope.callerKey().equals(caller.key())) {
            throw new TradeException(UNTRUSTED, "当前机器未获试点报价授权");
        }
        var prior = mapper.find(scope.tenantId(), commerceQuoteId);
        if (prior.isPresent()) {
            return receipt(prior.get(), ownerRef);
        }
        SourceEvidence evidence = source(commerceQuoteId);
        if (!matches(evidence, commerceQuoteId, ownerRef)) {
            throw new TradeException(CONFLICT, "商城报价归属或试点范围无法核对");
        }
        return transaction.execute(status -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            var quote = new QuoteReference(UUID.randomUUID().toString(), commerceQuoteId, ownerRef,
                    scope.version(), scope.storeId(), json.writeValueAsString(evidence), evidence.expiresAt(), now);
            mapper.insert(scope.tenantId(), quote);
            var persisted = mapper.lock(scope.tenantId(), commerceQuoteId)
                .orElseThrow(() -> new TradeException(UNAVAILABLE, "无法读取试点报价引用"));
            return receipt(persisted, ownerRef);
        });
    }

    private SourceEvidence source(String quoteId) {
        try {
            return commerce.get().uri(SOURCE_PATH, quoteId).retrieve().body(SourceEvidence.class);
        } catch (RestClientResponseException failure) {
            if (Set.of(404, 409).contains(failure.getStatusCode().value())) {
                throw new TradeException(CONFLICT, "商城报价已失效或不在试点范围");
            }
            throw new TradeException(UNAVAILABLE, "商城报价核验暂不可用");
        } catch (org.springframework.web.client.RestClientException failure) {
            throw new TradeException(UNAVAILABLE, "商城报价核验暂不可用");
        }
    }

    private boolean matches(SourceEvidence source, String quoteId, String ownerRef) {
        if (source == null || !quoteId.equals(source.commerceQuoteId()) || !ownerRef.equals(source.ownerRef())
                || !scope.version().equals(source.scopeVersion()) || !scope.commerceTenant().equals(source.commerceTenantId())
                || !Long.toString(scope.tenantId()).equals(source.tradeTenantId()) || !scope.merchantId().equals(source.merchantId())
                || !scope.storeId().equals(source.storeId()) || !scope.skuId().equals(source.eligibleSkuId())
                || !"CNY".equals(source.currency()) || source.storeVersion() < 0
                || source.expiresAt() == null || !source.expiresAt().isAfter(clock.instant())
                || source.items() == null || source.items().isEmpty() || source.items().size() > MAX_DISTINCT_ITEMS) {
            return false;
        }
        Set<String> unique = new HashSet<>();
        for (var item : source.items()) {
            if (!validLine(item) || !unique.add(item.skuId())) {
                return false;
            }
        }
        return unique.contains(scope.skuId());
    }

    private Prepared receipt(QuoteReference row, String ownerRef) {
        if (!scope.version().equals(row.scopeVersion()) || !scope.storeId().equals(row.storeId())
                || !ownerRef.equals(row.ownerRef()) || !clock.instant().isBefore(row.expiresAt())) {
            throw new TradeException(CONFLICT, "原报价引用已过期或不匹配当前结算");
        }
        return new Prepared(row.quoteRef(), row.commerceQuoteId(), row.scopeVersion(), row.storeId(), row.expiresAt());
    }

    private static boolean validLine(SourceLine line) {
        return line != null && identifier(line.skuId()) && line.skuRevision() > 0 && line.channelPriceVersion() >= 0
            && line.quantity() > 0 && line.quantity() <= 10000 && line.unitPriceMinor() != null
            && line.unitPriceMinor().matches("0|[1-9][0-9]{0,13}");
    }

    private static boolean validOrigin(URI url) {
        if (url.getHost() == null || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null
                || !Set.of("", "/").contains(url.getPath())) {
            return false;
        }
        return "https".equalsIgnoreCase(url.getScheme())
            || ("http".equalsIgnoreCase(url.getScheme()) && Set.of("localhost", "127.0.0.1", "[::1]").contains(url.getHost()));
    }

    private static boolean identifier(String value) {
        return value != null && value.matches(ID_PATTERN);
    }

    /** 启用时固定的部署范围，用于校验商城实际返回的归属。 */
    private record Scope(long tenantId, String callerKey, String version, String commerceTenant, String merchantId,
                         String storeId, String skuId) { }

    /** 仅表示已准备的报价引用，冻结价格和支付许可由后续交易报价步骤产生。 */
    public record Prepared(String tradeQuoteRef, String commerceQuoteId, String scopeVersion, String storeId, Instant expiresAt) { }

    /** 与商城受限读取契约一致，保留报价形成时的金额及版本证据。 */
    record SourceEvidence(String scopeVersion, String tradeTenantId, String commerceTenantId, String merchantId,
                          String storeId, long storeVersion, String eligibleSkuId, String commerceQuoteId,
                          String ownerRef, String currency, Instant expiresAt, List<SourceLine> items) { }

    /** 商品修订和渠道价格共同定位结算时采用的同一份价格快照。 */
    record SourceLine(String skuId, long skuRevision, long channelPriceVersion, int quantity, String unitPriceMinor) { }
}
