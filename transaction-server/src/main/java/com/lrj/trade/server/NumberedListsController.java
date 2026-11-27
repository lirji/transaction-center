package com.lrj.trade.server;

import com.lrj.trade.catalog.AdminQuoteQueryService;
import com.lrj.trade.catalog.CatalogService;
import com.lrj.trade.catalog.SkuService;
import com.lrj.trade.core.query.AdminNumberedPage;
import com.lrj.trade.core.query.AdminQuery;
import com.lrj.trade.inventory.InventoryService;
import com.lrj.trade.jobs.AdminJobQueryService;
import com.lrj.trade.member.MemberService;
import com.lrj.trade.member.ConsentDocumentService;
import com.lrj.trade.order.AdminReturnQueryService;
import com.lrj.trade.payment.AdminAttemptQueryService;
import com.lrj.trade.payment.AdminPaymentQueryService;
import com.lrj.trade.payment.AdminReceiptQueryService;
import com.lrj.trade.payment.ChannelConfigurationService;
import java.util.Map;
import com.lrj.trade.refund.AdminRefundQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 控制台页码查询按允许列表分发到领域服务，各领域继续掌管筛选和投影。 */
@RestController
@AdminRead
@RequestMapping("/api/v1/trade/admin/numbered-pages")
public class NumberedListsController {
    private final AdminPaymentQueryService payments;
    private final AdminReceiptQueryService receipts;
    private final AdminAttemptQueryService attempts;
    private final AdminReturnQueryService returns;
    private final AdminQuoteQueryService quotes;
    private final AdminRefundQueryService refunds;
    private final AdminJobQueryService jobs;
    private final CatalogService catalog;
    private final MemberService members;
    private final InventoryService inventory;
    private final SkuService skus;
    private final ConsentDocumentService consents;
    private final ChannelConfigurationService channels;

    /** 按领域调用公开用例，控制器不访问 Mapper 或业务表。 */
    public NumberedListsController(AdminPaymentQueryService payments, AdminReceiptQueryService receipts,
            AdminAttemptQueryService attempts, AdminReturnQueryService returns, AdminQuoteQueryService quotes,
            AdminRefundQueryService refunds, AdminJobQueryService jobs, CatalogService catalog, MemberService members,
            InventoryService inventory, SkuService skus, ConsentDocumentService consents, ChannelConfigurationService channels) {
        this.payments = payments;
        this.receipts = receipts;
        this.attempts = attempts;
        this.returns = returns;
        this.quotes = quotes;
        this.refunds = refunds;
        this.jobs = jobs;
        this.catalog = catalog;
        this.members = members;
        this.inventory = inventory;
        this.skus = skus;
        this.consents = consents;
        this.channels = channels;
    }

    /** 资源、筛选和排序使用既有业务校验，租户取自认证信息。 */
    @GetMapping("/{resource}")
    public AdminNumberedPage<?> page(@AuthenticationPrincipal Jwt jwt, @PathVariable String resource,
            @RequestParam MultiValueMap<String, String> params) {
        return switch (resource) {
            case "payments" -> page(jwt, params, "payment", payments::numberedPage);
            case "payment-receipts" -> page(jwt, params, "receipt", receipts::numberedPage);
            case "returns" -> page(jwt, params, "return", returns::numberedPage);
            case "quotes" -> page(jwt, params, "quote", quotes::numberedPage);
            case "refunds" -> page(jwt, params, "refund", refunds::numberedPage);
            case "jobs" -> page(jwt, params, "job", jobs::numberedPage);
            case "products" -> page(jwt, params, "product", catalog::numberedPage);
            case "members" -> page(jwt, params, "member", members::numberedPage);
            case "inventories" -> page(jwt, params, "inventory", inventory::numberedPage);
            case "warehouses" -> settings(jwt, params, (tenant, query, page) -> inventory.numberedWarehouses(tenant, query.limit(), page));
            case "consent-documents" -> settings(jwt, params, (tenant, query, page) -> consents.numberedDocuments(tenant, query.limit(), page));
            case "payment-channels" -> settings(jwt, params, (tenant, query, page) -> channels.numberedChannels(tenant, query.limit(), page));
            case "inventory-ledger" -> ledger(jwt, params);
            default -> throw AdminQuery.invalid();
        };
    }

    /** 子查询先核对支付意图归属，防止把外租户父资源显示为空列表。 */
    @GetMapping("/payment-attempts/{paymentNo}")
    public AdminNumberedPage<?> paymentAttempts(@AuthenticationPrincipal Jwt jwt, @PathVariable String paymentNo,
            @RequestParam MultiValueMap<String, String> params) {
        AdminQuery.text(paymentNo, 64);
        return page(jwt, params, "attempt", (tenant, query, page) -> attempts.numberedPage(tenant, query, page, paymentNo));
    }

    /** 后台规格明确限定租户和父商品，只读取草稿投影。 */
    @GetMapping("/product-skus/{productId}")
    public AdminNumberedPage<?> productSkus(@AuthenticationPrincipal Jwt jwt, @PathVariable String productId,
            @RequestParam MultiValueMap<String, String> params) {
        long id = AdminQuery.number(productId, false);
        return settings(jwt, params, (tenant, query, page) -> skus.numberedDraftSkus(tenant, id, query.limit(), page));
    }

    private AdminNumberedPage<?> ledger(Jwt jwt, MultiValueMap<String, String> params) {
        var numbered = AdminQueryParameters.numbered(params,
                input -> AdminQuery.parse(input, AdminQuery.fields("warehouseId", "skuId"), false));
        var query = numbered.query();
        if (!(query.filters().get("warehouseId") instanceof Long warehouse)
                || !(query.filters().get("skuId") instanceof Long sku)) throw AdminQuery.invalid();
        return inventory.numberedLedger(tenant(jwt), warehouse, sku, query.limit(), numbered.page());
    }

    private static AdminNumberedPage<?> settings(Jwt jwt, MultiValueMap<String, String> params, Query read) {
        var numbered = AdminQueryParameters.numbered(params, input -> AdminQuery.parse(input, Map.of(), false));
        return read.page(tenant(jwt), numbered.query(), numbered.page());
    }

    private static AdminNumberedPage<?> page(Jwt jwt, MultiValueMap<String, String> params, String kind, Query read) {
        var request = AdminQueryParameters.numbered(params, filters -> AdminQueryParameters.parse(kind, filters));
        return read.page(tenant(jwt), request.query(), request.page());
    }

    private static long tenant(Jwt jwt) {
        return AdminQuery.number(jwt.getClaimAsString("tenant_id"), false);
    }

    private interface Query {
        AdminNumberedPage<?> page(long tenant, AdminQuery query, long page);
    }
}
