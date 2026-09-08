package com.lrj.trade.server;

import com.lrj.trade.inventory.model.LedgerEntry;

import com.lrj.trade.inventory.model.Warehouse;

import com.lrj.trade.inventory.model.Balance;

import com.lrj.trade.catalog.model.Product;

import com.lrj.trade.member.model.Document;

import com.lrj.trade.member.model.Consent;

import com.lrj.trade.member.model.Member;

import com.lrj.trade.core.*;
import com.lrj.trade.member.MemberService;
import com.lrj.trade.member.ConsentDocumentService;
import com.lrj.trade.catalog.CatalogService;
import com.lrj.trade.catalog.SkuService;
import com.lrj.trade.catalog.QuoteService;
import com.lrj.trade.catalog.model.Sku;
import com.lrj.trade.catalog.model.Quote;
import com.lrj.trade.catalog.model.QuoteSelection;
import com.lrj.trade.inventory.InventoryService;
import com.lrj.trade.order.MerchantService;
import com.lrj.trade.order.OrderService;
import com.lrj.trade.order.model.Merchant;
import com.lrj.trade.order.model.OrderHeader;
import com.lrj.trade.order.model.OrderView;
import com.lrj.trade.order.model.ShippingAddress;
import com.lrj.trade.payment.PaymentService;
import com.lrj.trade.payment.ChannelConfigurationService;
import com.lrj.trade.payment.model.PaymentView;
import com.lrj.trade.payment.model.ChannelView;
import com.lrj.trade.refund.RefundService;
import com.lrj.trade.refund.RefundPolicyService;
import com.lrj.trade.refund.model.RefundView;
import com.lrj.trade.refund.model.RefundQueueItem;
import com.lrj.trade.refund.model.RefundPolicy;
import com.lrj.trade.refund.model.RefundSelection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 首批HTTP适配层；会员与租户只能来自已经验签的JWT声明。 */
@RestController
@RequestMapping("/api/v1/trade")
public class TradeController {
    private final MemberService member;
    private final CatalogService catalog;
    private final com.lrj.trade.catalog.CatalogQueryService catalogQueries;
    private final InventoryService inventory;
    private final ConsentDocumentService consentDocuments;
    private final SkuService skus;
    private final QuoteService quotes;
    private final MerchantService merchants;
    private final OrderService orders;
    private final PaymentService payments;
    private final ChannelConfigurationService channels;
    private final RefundService refunds;
    private final RefundPolicyService refundPolicies;
    private final RefundApprovalIngress approvalIngress;
    /** 只依赖公开应用服务，不在控制器中拼装SQL或直接操纵库存。 */
    public TradeController(MemberService member,CatalogService catalog,com.lrj.trade.catalog.CatalogQueryService catalogQueries,InventoryService inventory,ConsentDocumentService consentDocuments,
                           SkuService skus,QuoteService quotes,MerchantService merchants,OrderService orders,
                           PaymentService payments,ChannelConfigurationService channels,RefundService refunds,RefundPolicyService refundPolicies,RefundApprovalIngress approvalIngress) {
        this.member=member; this.catalog=catalog; this.catalogQueries=catalogQueries; this.inventory=inventory; this.consentDocuments=consentDocuments;
        this.skus=skus; this.quotes=quotes;
        this.merchants=merchants; this.orders=orders;
        this.payments=payments; this.channels=channels;
        this.refunds=refunds; this.refundPolicies=refundPolicies;
        this.approvalIngress=approvalIngress;
    }
    /** 业务注册幂等键为登录身份，重复注册不会覆盖原展示资料。 */
    @PostMapping("/members:register")
    public Resource register(@AuthenticationPrincipal Jwt jwt,@RequestBody Registration body) {
        return new Resource(member.register(actor(jwt),body.displayName()));
    }
    /** 返回当前可信登录主体自己的会员，不接受查询他人会员ID。 */
    @GetMapping("/members/me")
    public Member me(@AuthenticationPrincipal Jwt jwt) { return member.me(actor(jwt)); }
    /** 只能修改当前会员的展示资料；不能通过请求体改租户、身份或消费历史。 */
    @PatchMapping("/members/me")
    public Resource profile(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ProfileInput body) {
        return new Resource(member.updateProfile(actor(jwt),key,body.expectedVersion(),body.displayName()));
    }
    /** 后台会员列表不返回认证主体详情。 */
    @GetMapping("/admin/members")
    public List<Member> members(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") long after,
            @RequestParam(defaultValue="20") int limit) {
        return member.list(actor(jwt).tenantId(),after,limit);
    }
    /** 状态操作在服务内校验租户、版本及合法状态，HTTP层另需管理作用域。 */
    @PostMapping("/admin/members/{id}:change-status")
    public Resource memberStatus(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @PathVariable long id,@RequestHeader(value="If-Match",required=false) String match,@Valid @RequestBody MemberStatusInput body) {
        return new Resource(AdminVersion.execute(match,body.expectedVersion(),()->member.changeStatus(actor(jwt),key,id,body.expectedVersion(),body.targetStatus(),body.reason())));
    }
    /** 同意或撤回引用已发布的条款版本，幂等重放不会二次改变状态。 */
    @PostMapping("/members/me/consents")
    public Resource consent(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ConsentInput body) {
        return new Resource(member.consent(actor(jwt),key,body.expectedVersion(),body.purpose(),body.documentVersion(),body.action()));
    }
    /** 会员可查询本人的同意历史，包括冻结状态下的历史。 */
    @GetMapping("/members/me/consents")
    public List<Consent> consents(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") long after,
            @RequestParam(defaultValue="20") int limit) {
        return member.consents(actor(jwt),after,limit);
    }
    /** 条款发布是管理操作，发布后不提供覆盖和删除历史内容的入口。 */
    @PostMapping("/admin/consent-documents")
    public Resource publishConsentDocument(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @RequestBody ConsentDocumentInput body) {
        return new Resource(consentDocuments.publish(actor(jwt),key,body.purpose(),body.documentVersion(),body.title(),body.contentUrl(),body.contentHash()));
    }
    /** 查询条款供前端展示，不把任何一个版本暗中当成会员已同意。 */
    @GetMapping("/consent-documents")
    public List<Document> consentDocuments(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="20") int limit) {
        return consentDocuments.list(actor(jwt).tenantId(),after,limit);
    }
    /** 创建单SKU商品草稿，后续扩展多规格时保持已有合同兼容。 */
    @PostMapping("/admin/products")
    public Resource create(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ProductInput body) {
        return new Resource(catalog.create(actor(jwt),key,body.productNo(),body.name(),body.skuNo(),body.unitPrice(),body.currency()));
    }
    /** 修改草稿须提供上次读取的版本，避免后台编辑相互覆盖。 */
    @PatchMapping("/admin/products/{id}")
    public Resource edit(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
                         @PathVariable long id,@RequestHeader(value="If-Match",required=false) String match,@Valid @RequestBody ProductEdit body) {
        return new Resource(AdminVersion.execute(match,body.expectedVersion(),()->catalog.edit(actor(jwt),key,id,body.expectedVersion(),body.name(),body.unitPrice(),body.currency())));
    }
    /** 发布命令创建不可变SKU快照。 */
    @PostMapping("/admin/products/{id}:publish")
    public Resource publish(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
                            @PathVariable long id,@RequestHeader(value="If-Match",required=false) String match,@Valid @RequestBody VersionInput body) {
        return new Resource(AdminVersion.execute(match,body.expectedVersion(),()->catalog.publish(actor(jwt),key,id,body.expectedVersion())));
    }
    /** 下架不清理库存或历史发布快照。 */
    @PostMapping("/admin/products/{id}:unpublish")
    public Resource unpublish(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
                              @PathVariable long id,@RequestHeader(value="If-Match",required=false) String match,@Valid @RequestBody VersionInput body) {
        return new Resource(AdminVersion.execute(match,body.expectedVersion(),()->catalog.unpublish(actor(jwt),key,id,body.expectedVersion())));
    }
    /** 会员侧列表仅显示已发布商品，采用有界游标。 */
    @GetMapping("/products")
    public List<Product> products(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="20") int limit) {
        return catalogQueries.products(actor(jwt).tenantId(),after,limit);
    }
    /** 会员详情隐藏下架商品，不允许通过精确ID绕过列表可售过滤。 */
    @GetMapping("/products/{id}")
    public Product product(@AuthenticationPrincipal Jwt jwt,@PathVariable long id) {
        return catalogQueries.product(actor(jwt).tenantId(),id);
    }
    /** 管理商品详情包含草稿，仍限制为当前租户。 */
    @GetMapping("/admin/products/{id}")
    public org.springframework.http.ResponseEntity<Product> adminProduct(@AuthenticationPrincipal Jwt jwt,@PathVariable long id) {
        var value=catalog.detail(actor(jwt).tenantId(),id,true);
        return org.springframework.http.ResponseEntity.ok().eTag("\""+value.version()+"\"").body(value);
    }
    /** 管理列表允许查看本租户草稿，不改变会员侧过滤规则。 */
    @GetMapping("/admin/products")
    public List<Product> adminProducts(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="20") int limit) {
        return catalog.list(actor(jwt).tenantId(),after,limit,true);
    }
    /** 仓库独立于库存余额创建，避免把库存初始化误当仓库存在。 */
    @PostMapping("/admin/warehouses")
    public Resource warehouse(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@RequestBody WarehouseInput body) {
        return new Resource(inventory.warehouse(actor(jwt),key,body.warehouseNo(),body.name()));
    }
    /** 返回数据库中的仓库列表，供库存页面选择且不暴露跨租户资源。 */
    @GetMapping("/admin/warehouses")
    public List<Warehouse> warehouses(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="20") int limit) {
        return inventory.warehouses(actor(jwt).tenantId(),after,limit);
    }
    /** 按仓库与SKU查看库存流水，不提供无过滤的大表导出入口。 */
    @GetMapping("/admin/inventory-ledgers")
    public List<LedgerEntry> ledgers(@AuthenticationPrincipal Jwt jwt,@RequestParam long warehouseId,
            @RequestParam long skuId,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="20") int limit) {
        return inventory.ledgers(actor(jwt).tenantId(),warehouseId,skuId,after,limit);
    }
    /** 盘点必须附原因并持久化操作流水；不开放前端库存预占接口。 */
    @PostMapping("/admin/inventory-adjustments")
    public Resource adjust(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@RequestBody Adjustment body) {
        return new Resource(inventory.adjust(actor(jwt),key,body.warehouseId(),body.skuId(),body.delta(),body.reason()));
    }
    /** 查询只能展示余额，不能通过GET隐式修复或释放库存。 */
    @GetMapping("/admin/inventories")
    public List<Balance> balances(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="20") int limit) {
        return inventory.balances(actor(jwt).tenantId(),after,limit);
    }
    /** 指定商品版本添加规格，不能编辑线上商品，也不能改变其他规格。 */
    @PostMapping("/admin/products/{id}/skus")
    public Resource addSku(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @PathVariable long id,@RequestHeader(value="If-Match",required=false) String match,@Valid @RequestBody SkuInput body) {
        return new Resource(AdminVersion.execute(match,body.expectedVersion(),()->skus.add(actor(jwt),key,id,body.expectedVersion(),body.skuNo(),body.unitPrice(),
            body.currency(),body.stockType(),body.specification())));
    }
    /** 编辑明确的规格编号，防止一份价格请求覆盖同商品的全部规格。 */
    @PatchMapping("/admin/products/{id}/skus/{skuId}")
    public Resource editSku(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @PathVariable long id,@PathVariable long skuId,@RequestHeader(value="If-Match",required=false) String match,@Valid @RequestBody SkuEdit body) {
        return new Resource(AdminVersion.execute(match,body.expectedVersion(),()->skus.edit(actor(jwt),key,id,skuId,body.expectedVersion(),body.unitPrice(),
            body.currency(),body.stockType(),body.specification())));
    }
    /** 后台读取本租户草稿规格，revision为整体商品版本。 */
    @GetMapping("/admin/products/{id}/skus")
    public List<Sku> adminSkus(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,
            @RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="20") int limit) {
        return skus.list(actor(jwt).tenantId(),id,after,limit,true);
    }
    /** 会员规格列表只读取已发布快照，未发布或下架返回空列表。 */
    @GetMapping("/products/{id}/skus")
    public List<Sku> skus(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,
            @RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="20") int limit) {
        return catalogQueries.skus(actor(jwt).tenantId(),id,after,limit);
    }
    /** 客户端只提交规格及数量，金额由服务端固定价策略计算并持久化。 */
    @PostMapping("/quotes")
    public Quote quote(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @RequestBody QuoteInput body) {
        return quotes.create(actor(jwt),key,body.items());
    }
    /** 读取本人原报价，过期或冻结不改写历史，重新报价必须使用新的业务动作键。 */
    @GetMapping("/quotes/{id}")
    public Quote quote(@AuthenticationPrincipal Jwt jwt,@PathVariable long id) {
        return quotes.read(actor(jwt),id);
    }
    /** 租户管理员显式配置逻辑商户，不能由会员下单请求决定收款主体。 */
    @PostMapping("/admin/merchant")
    public Resource merchant(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @RequestBody MerchantInput body) {
        return new Resource(merchants.create(actor(jwt),key,body.merchantNo(),body.name()));
    }
    /** 商户配置只对本租户管理端可见，不返回渠道密钥。 */
    @GetMapping("/admin/merchant")
    public org.springframework.http.ResponseEntity<Merchant> merchant(@AuthenticationPrincipal Jwt jwt) { var value=merchants.read(actor(jwt).tenantId());return org.springframework.http.ResponseEntity.ok().eTag("\""+value.version()+"\"").body(value); }
    /** 冻结商户阻止新订单，不删除历史资金事实。 */
    @PostMapping("/admin/merchant:change-status")
    public Resource merchantStatus(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value="If-Match",required=false) String match,@Valid @RequestBody MemberStatusInput body) {
        return new Resource(AdminVersion.execute(match,body.expectedVersion(),()->merchants.changeStatus(actor(jwt),key,body.expectedVersion(),body.targetStatus(),body.reason())));
    }
    /** 订单只引用服务端报价，全部实物SKU预占与订单同事务提交。 */
    @PostMapping("/orders")
    public OrderView order(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody OrderInput body) {
        return orders.create(actor(jwt),key,identifier(body.quoteId()),
            body.warehouseId()==null?null:identifier(body.warehouseId()),body.address(),body.referralToken());
    }
    /** 本人订单有界分页，不在列表里解密收货信息或查询渠道。 */
    @GetMapping("/orders")
    public List<OrderHeader> orders(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") long after,
            @RequestParam(defaultValue="20") int limit) { return orders.list(actor(jwt),after,limit); }
    /** 精确读取本人冻结商品和价格快照，不暴露地址明文。 */
    @GetMapping("/orders/{orderNo}")
    public OrderView order(@AuthenticationPrincipal Jwt jwt,@PathVariable String orderNo) {
        return orders.read(actor(jwt),orderNo);
    }
    /** 取消可能仅进入CLOSING，前端不能把受理响应解释成已经关单释放。 */
    @PostMapping("/orders/{orderNo}:cancel")
    public OrderView cancelOrder(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @PathVariable String orderNo,@RequestBody CancelInput body) {
        return orders.cancel(actor(jwt),key,orderNo,body.reason());
    }
    /** 仅后台管理员预登记商户渠道和回跳地址，不触发渠道网络请求。 */
    @PostMapping("/admin/payment-channels")
    public Resource configureChannel(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @RequestBody ChannelInput body) {
        return new Resource(channels.create(actor(jwt),key,body.channelCode(),body.channelMerchantNo(),body.currency(),
            body.credentialRef(),body.returnUrlId(),body.returnUrl()));
    }
    /** 渠道列表不返回密钥托管引用或直接可执行的支付参数。 */
    @GetMapping("/admin/payment-channels")
    public List<ChannelView> paymentChannels(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") long after,
            @RequestParam(defaultValue="20") int limit) {
        return channels.list(actor(jwt).tenantId(),after,limit);
    }
    /** 异步受理支付，不因本地受理成功而宣称渠道已支付。 */
    @PostMapping("/orders/{orderNo}/payments")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public PaymentView createPayment(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @PathVariable String orderNo,@RequestBody PaymentInput body) {
        return payments.create(actor(jwt),key,orderNo,body.channelCode(),body.returnUrlId());
    }
    /** 查询本人本地支付事实，回跳页面不能写成功状态。 */
    @GetMapping("/payments/{paymentNo}")
    public PaymentView payment(@AuthenticationPrincipal Jwt jwt,@PathVariable String paymentNo) {
        return payments.read(actor(jwt),paymentNo);
    }
    /** 本人退款申请仅代表本地受理，待审批或渠道处理中均不是退款成功。 */
    @PostMapping("/orders/{orderNo}/refunds")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public RefundView requestRefund(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @PathVariable String orderNo,@Valid @RequestBody RefundInput body) {
        return refunds.request(actor(jwt),key,orderNo,body.amount(),body.items()==null?null:body.items().stream()
            .map(i->new RefundSelection(i.componentType(),i.orderItemId(),i.quantity(),i.amount())).toList(),body.reason());
    }
    /** 当前仅本人读取申请；审批结果和渠道成功不开放给会员提交。 */
    @GetMapping("/refunds/{refundNo}")
    public RefundView refund(@AuthenticationPrincipal Jwt jwt,@PathVariable String refundNo) { return refunds.read(actor(jwt),refundNo); }
    /** 专用流程作用域接收签名回执；同步事务提交后返回编号，不能提交任意审批人或结果字段。 */
    @PostMapping("/integrations/refund-approvals")
    public Resource approvalReceipt(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody ApprovalReceiptInput body) {
        return new Resource(approvalIngress.accept(actor(jwt),body.signedReceipt()));
    }
    /** 只接受完整签名载荷；大小限制先于远端JWKS请求，未知JSON字段按既有严格配置拒绝。 */
    public record ApprovalReceiptInput(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=16384) String signedReceipt) {}
    /** 后台按固定状态查询有界队列；租户来自验签JWT，不提供审批写入或资金终态修改。 */
    @GetMapping("/admin/refunds")
    public List<RefundQueueItem> refundQueue(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="PENDING_APPROVAL") String status,@RequestParam(defaultValue="0") long after,
            @RequestParam(defaultValue="20") int limit) {
        return refunds.listQueue(actor(jwt).tenantId(),status,after,limit);
    }
    /** 后台读取免审策略，未配置时零版本零门槛要求审批。 */
    @GetMapping("/admin/refund-policy")
    public org.springframework.http.ResponseEntity<RefundPolicy> refundPolicy(@AuthenticationPrincipal Jwt jwt) { var value=refundPolicies.read(actor(jwt).tenantId());return org.springframework.http.ResponseEntity.ok().eTag("\""+value.version()+"\"").body(value); }
    /** 管理作用域加幂等和版本变更，策略只影响新申请，不自动批准旧申请。 */
    @PatchMapping("/admin/refund-policy")
    public RefundPolicy configureRefundPolicy(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value="If-Match",required=false) String match,@Valid @RequestBody RefundPolicyInput body) {
        return AdminVersion.execute(match,body.expectedVersion(),()->refundPolicies.configure(actor(jwt),key,body.currency(),body.autoApproveLimit(),body.expectedVersion()));
    }
    private Actor actor(Jwt jwt) {
        try {
            String tenant=jwt.getClaimAsString("tenant_id");
            if(jwt.getIssuer()==null || jwt.getSubject()==null) throw new IllegalArgumentException();
            return new Actor(Long.parseLong(tenant),jwt.getIssuer().toString(),jwt.getSubject());
        } catch(RuntimeException invalid) { throw new TradeException("INVALID_IDENTITY","令牌缺少有效租户或主体声明"); }
    }
    private long identifier(String value) {
        if(value==null || !value.matches("[1-9][0-9]{0,18}")) throw new TradeException("INVALID_ARGUMENT","资源编号无效");
        try { return Long.parseLong(value); }
        catch(NumberFormatException invalid) { throw new TradeException("INVALID_ARGUMENT","资源编号超限"); }
    }
    /** 写命令返回稳定资源引用，客户端再GET当前视图。 */
    public record Resource(String resourceId) {}
    /** 注册输入不接受tenantId或subject。 */
    public record Registration(String displayName) {}
    /** 首期单SKU草稿输入，金额由服务端做整数边界检查。 */
    public record ProductInput(String productNo,String name,String skuNo,@NotNull Long unitPrice,String currency) {}
    /** 变更草稿的乐观版本与价格信息。 */
    public record ProductEdit(@NotNull Long expectedVersion,String name,@NotNull Long unitPrice,String currency) {}
    /** 状态命令版本条件，旧版本不能覆盖新发布。 */
    public record VersionInput(@NotNull Long expectedVersion) {}
    /** 仓库业务编号和可读名称。 */
    public record WarehouseInput(String warehouseNo,String name) {}
    /** 数量调整输入必须保留人工业务原因。 */
    public record Adjustment(long warehouseId,long skuId,long delta,String reason) {}
    /** 资料修改必须带版本，防止用缺省零值覆盖已更新资料。 */
    public record ProfileInput(@NotNull Long expectedVersion,String displayName) {}
    /** 后台冻结或解冻动作必须提供原因和会员版本。 */
    public record MemberStatusInput(@NotNull Long expectedVersion,String targetStatus,String reason) {}
    /** 同意动作与资料修改共享会员版本，以明确并发顺序。 */
    public record ConsentInput(@NotNull Long expectedVersion,String purpose,String documentVersion,String action) {}
    /** 条款发布输入只保存HTTPS内容引用和摘要，不允许直接覆盖旧版本。 */
    public record ConsentDocumentInput(String purpose,String documentVersion,String title,String contentUrl,String contentHash) {}
    /** 新规格必须指定库存类型与规格对象，空规格使用空对象而非省略字段。 */
    public record SkuInput(@NotNull Long expectedVersion,String skuNo,@NotNull Long unitPrice,String currency,
                           String stockType,Map<String,String> specification) {}
    /** 修改不能改变规格业务编号；版本为所属商品的当前版本。 */
    public record SkuEdit(@NotNull Long expectedVersion,@NotNull Long unitPrice,String currency,
                          String stockType,Map<String,String> specification) {}
    /** 不接受客户端价格、币种、租户或会员字段，明细校验在领域入口统一执行。 */
    public record QuoteInput(List<QuoteSelection> items) {}
    /** 逻辑商户编号不等于真实支付渠道商户授权。 */
    public record MerchantInput(String merchantNo,String name) {}
    /** 实物必须有仓库及地址，纯服务不能附加无用途的个人信息。 */
    public record OrderInput(@NotNull String quoteId,String warehouseId,ShippingAddress address,String referralToken) {
        /** 保持内部旧调用构造兼容，缺省令牌不改变订单含义。 */
        public OrderInput(String quoteId,String warehouseId,ShippingAddress address) { this(quoteId,warehouseId,address,null); }
        /** 请求对象即使被误记日志也不得输出地址。 */
        @Override public String toString() { return "OrderInput[REDACTED]"; }
    }
    /** 取消原因作为审计依据，不接受客户端指定目标状态。 */
    public record CancelInput(String reason) {}
    /** 只能选择预登记渠道和回跳别名，不接收应付金额或任意回跳URL。 */
    public record PaymentInput(String channelCode,String returnUrlId) {}
    /** 禁止客户端指定租户、收款、渠道或审批状态；金额仍按原订单组件验证。 */
    public record RefundInput(@NotNull Long amount,List<@NotNull @Valid RefundItemInput> items,String reason) {
        /** 退款原因可能由用户填写，默认不输出到日志。 */
        @Override public String toString() { return "RefundInput[REDACTED]"; }
    }
    /** HTTP显式要求金额和数量，不能将缺字段默认为零后静默接受。 */
    public record RefundItemInput(String componentType,String orderItemId,@NotNull Long quantity,@NotNull Long amount) {}
    /** 策略金额按订单成功加在途累计比较，零门槛不代表无限免审。 */
    public record RefundPolicyInput(String currency,@NotNull Long autoApproveLimit,@NotNull Long expectedVersion) {}
    /** 管理配置不接收密钥内容，托管引用也不输出默认日志。 */
    public record ChannelInput(String channelCode,String channelMerchantNo,String currency,String credentialRef,String returnUrlId,String returnUrl) {
        /** 避免配置被错误记录到访问日志。 */
        @Override public String toString() { return "ChannelInput[REDACTED]"; }
    }
}
