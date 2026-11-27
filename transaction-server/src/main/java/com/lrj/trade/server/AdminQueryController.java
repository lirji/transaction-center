package com.lrj.trade.server;
import com.lrj.trade.core.*;
import com.lrj.trade.core.query.*;
import com.lrj.trade.order.*;
import com.lrj.trade.payment.*;
import com.lrj.trade.refund.*;
import com.lrj.trade.catalog.*;
import com.lrj.trade.jobs.*;
import com.lrj.trade.server.checkout.CheckoutPilotAuditLookup;
import java.util.*;
import java.time.Instant;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** 管理查询独立于会员入口；只依赖公开应用服务，不越过领域Port读取Mapper。 */
@RestController
@AdminRead
@RequestMapping("/api/v1/trade/admin")
public class AdminQueryController {
    private final AdminOrderQueryService order;
    private final AdminReturnQueryService returns;
    private final AdminPaymentQueryService payment;
    private final AdminReceiptQueryService receipt;
    private final AdminQuoteQueryService quote;
    private final AdminJobQueryService job;
    private final AdminAttemptQueryService attempt;
    private final AdminRefundQueryService refund;
    private final AuditWriter operationAudit;
    private final CheckoutPilotAuditLookup checkoutPilot;
    private final OrderReferralEvidenceService evidence;private final CustomerEligibilityService eligibility;private final AdminEvidenceAccess evidenceAccess;
    /** 管理员证据有独立入口授权，复用交易事实算法而非内部HTTP身份。 */
    public AdminQueryController(AdminOrderQueryService order,AdminReturnQueryService returns,AdminPaymentQueryService payment,AdminReceiptQueryService receipt,AdminQuoteQueryService quote,AdminJobQueryService job,AdminAttemptQueryService attempt,AdminRefundQueryService refund,OrderReferralEvidenceService evidence,CustomerEligibilityService eligibility,AdminEvidenceAccess evidenceAccess,AuditWriter operationAudit,CheckoutPilotAuditLookup checkoutPilot) {
        this.order=order;
        this.returns=returns;
        this.payment=payment;
        this.receipt=receipt;
        this.quote=quote;
        this.job=job;
        this.attempt=attempt;
        this.refund=refund;
        this.operationAudit=operationAudit;
        this.checkoutPilot=checkoutPilot;
        this.evidence=evidence;this.eligibility=eligibility;this.evidenceAccess=evidenceAccess;
    }
    /** 有界租户查询，精确筛选不接受任意列名或自由文本搜索。 */
    @GetMapping("/orders")
    public AdminPage<com.lrj.trade.order.model.AdminOrderView> orderList(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> params) {
        return order.page(actor(jwt).tenantId(),AdminQueryParameters.parse("order",params));
    }
    /** 详情只返回安全投影，不解密地址或渠道凭据。 */
    @GetMapping("/orders/{orderNo}")
    public AdminOrderQueryService.Detail orderDetail(@AuthenticationPrincipal Jwt jwt,@PathVariable String orderNo,@RequestParam MultiValueMap<String,String> params) {
        AdminQueryParameters.none(params);AdminQuery.text(orderNo,64);
        return order.detail(actor(jwt).tenantId(),orderNo);
    }
    /** 有界租户查询，精确筛选不接受任意列名或自由文本搜索。 */
    @GetMapping("/returns")
    public AdminPage<com.lrj.trade.order.model.AdminReturnView> returnList(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> params) {
        return returns.page(actor(jwt).tenantId(),AdminQueryParameters.parse("return",params));
    }
    /** 详情只返回安全投影，不解密地址或渠道凭据。 */
    @GetMapping("/returns/{returnNo}")
    public org.springframework.http.ResponseEntity<AdminReturnQueryService.Detail> returnDetail(@AuthenticationPrincipal Jwt jwt,@PathVariable String returnNo,@RequestParam MultiValueMap<String,String> params) {
        AdminQueryParameters.none(params);AdminQuery.text(returnNo,64);
        var value=returns.detail(actor(jwt).tenantId(),returnNo);return org.springframework.http.ResponseEntity.ok().eTag("\""+value.summary().version()+"\"").body(value);
    }
    /** 有界租户查询，精确筛选不接受任意列名或自由文本搜索。 */
    @GetMapping("/payments")
    public AdminPage<com.lrj.trade.payment.model.AdminPaymentView> paymentList(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> params) {
        return payment.page(actor(jwt).tenantId(),AdminQueryParameters.parse("payment",params));
    }
    /** 详情只返回安全投影，不解密地址或渠道凭据。 */
    @GetMapping("/payments/{paymentNo}")
    public AdminPaymentQueryService.Detail paymentDetail(@AuthenticationPrincipal Jwt jwt,@PathVariable String paymentNo,@RequestParam MultiValueMap<String,String> params) {
        AdminQueryParameters.none(params);AdminQuery.text(paymentNo,64);
        return payment.detail(actor(jwt).tenantId(),paymentNo);
    }
    /** 有界租户查询，精确筛选不接受任意列名或自由文本搜索。 */
    @GetMapping("/payment-receipts")
    public AdminPage<com.lrj.trade.payment.model.AdminReceiptView> receiptList(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> params) {
        return receipt.page(actor(jwt).tenantId(),AdminQueryParameters.parse("receipt",params));
    }
    /** 详情只返回安全投影，不解密地址或渠道凭据。 */
    @GetMapping("/payment-receipts/{receiptNo}")
    public AdminReceiptQueryService.Detail receiptDetail(@AuthenticationPrincipal Jwt jwt,@PathVariable String receiptNo,@RequestParam MultiValueMap<String,String> params) {
        AdminQueryParameters.none(params);AdminQuery.text(receiptNo,64);
        return receipt.detail(actor(jwt).tenantId(),receiptNo);
    }
    /** 有界租户查询，精确筛选不接受任意列名或自由文本搜索。 */
    @GetMapping("/quotes")
    public AdminPage<com.lrj.trade.catalog.model.AdminQuoteView> quoteList(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> params) {
        return quote.page(actor(jwt).tenantId(),AdminQueryParameters.parse("quote",params));
    }
    /** 详情只返回安全投影，不解密地址或渠道凭据。 */
    @GetMapping("/quotes/{quoteId}")
    public AdminQuoteQueryService.Detail quoteDetail(@AuthenticationPrincipal Jwt jwt,@PathVariable String quoteId,@RequestParam MultiValueMap<String,String> params) {
        AdminQueryParameters.none(params);
        if(!BusinessNumber.matches(BusinessNumber.Type.QUOTE,quoteId)) AdminQuery.number(quoteId,false);
        return quote.detail(actor(jwt).tenantId(),quoteId);
    }
    /** 有界租户查询，精确筛选不接受任意列名或自由文本搜索。 */
    @GetMapping("/jobs")
    public AdminPage<com.lrj.trade.jobs.model.AdminJobView> jobList(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> params) {
        return job.page(actor(jwt).tenantId(),AdminQueryParameters.parse("job",params));
    }
    /** 详情只返回安全投影，不解密地址或渠道凭据。 */
    @GetMapping("/jobs/{jobId}")
    public AdminJobQueryService.Detail jobDetail(@AuthenticationPrincipal Jwt jwt,@PathVariable String jobId,@RequestParam MultiValueMap<String,String> params) {
        AdminQueryParameters.none(params);AdminQuery.number(jobId,false);
        return job.detail(actor(jwt).tenantId(),jobId);
    }
    /** 子集合先校验支付父资源租户，空列表不能掩盖父资源越权。 */
    @GetMapping("/payments/{paymentNo}/attempts")
    public AdminPage<com.lrj.trade.payment.model.AdminAttemptView> attempts(@AuthenticationPrincipal Jwt jwt,@PathVariable String paymentNo,@RequestParam MultiValueMap<String,String> params) {
        AdminQuery.text(paymentNo,64);return attempt.page(actor(jwt).tenantId(),AdminQueryParameters.parse("attempt",params),paymentNo);
    }
    /** 保持旧队列数组和默认状态，筛选扩展不会改变旧页面语义。 */
    @GetMapping("/refunds")
    public List<com.lrj.trade.refund.model.RefundQueueItem> refunds(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> params) {
        return refund.queue(actor(jwt).tenantId(),AdminQueryParameters.parse("refund",params));
    }
    /** 独立管理退款详情，不把管理员伪装成申请会员。 */
    @GetMapping("/refunds/{refundNo}")
    public AdminRefundQueryService.Detail refund(@AuthenticationPrincipal Jwt jwt,@PathVariable String refundNo,@RequestParam MultiValueMap<String,String> params) {
        AdminQueryParameters.none(params);AdminQuery.text(refundNo,64);return refund.detail(actor(jwt).tenantId(),refundNo);
    }
    /** 审计查询先按租户和可读业务编号解析真实退款父资源。 */
    @GetMapping("/refunds/{refundNo}/operation-audits")
    public AuditWriter.History refundAudit(@AuthenticationPrincipal Jwt jwt,@PathVariable String refundNo,
                                          @RequestParam MultiValueMap<String,String> params) {
        AdminQuery.text(refundNo,64);
        var cursors=params.get("cursor");
        if(params.size()!=(cursors==null?0:1) || cursors!=null && cursors.size()!=1)
            throw new TradeException("INVALID_ARGUMENT","审计查询参数无效");
        Actor actor=actor(jwt);
        var owned=refund.read(actor.tenantId(),refundNo);
        return operationAudit.refundHistory(actor,owned.refundNo(),cursors==null?null:cursors.getFirst());
    }
    /** 同单读取复用已授权的订单、收款和退款查询，以已持久化的报价绑定标记商城来源。 */
    @GetMapping("/orders/{orderNo}/operation-evidence")
    public OrderOperationEvidence orderOperationEvidence(@AuthenticationPrincipal Jwt jwt,@PathVariable String orderNo,
            @RequestParam MultiValueMap<String,String> params) {
        AdminQuery.text(orderNo,64);
        if(params.entrySet().stream().anyMatch(entry->!Set.of("receiptAfter","refundAfter").contains(entry.getKey())
                ||entry.getValue().size()!=1)) throw new TradeException("INVALID_ARGUMENT","审计查询参数无效");
        long receiptAfter=params.containsKey("receiptAfter")?AdminQuery.number(params.getFirst("receiptAfter"),true):0;
        long refundAfter=params.containsKey("refundAfter")?AdminQuery.number(params.getFirst("refundAfter"),true):0;
        Actor actor=actor(jwt);
        var header=order.read(actor.tenantId(),orderNo);
        return operationAudit.readOrderEvidence(actor,header.orderNo(),()->{
            var binding=checkoutPilot.find(actor.tenantId(),Long.parseLong(header.orderId())).orElse(null);
            var receipts=receipt.page(actor.tenantId(),new AdminQuery(receiptAfter,20,null,null,Map.of("orderNo",header.orderNo())));
            var refunds=refund.page(actor.tenantId(),new AdminQuery(refundAfter,20,null,null,Map.of("orderNo",header.orderNo(),"status","ALL")));
            return new OrderOperationEvidence(Long.toString(actor.tenantId()),header.orderId(),header.orderNo(),header.businessNo(),
                header.status(),header.financialStatus(),header.currency(),header.createdAt(),header.paidAt(),binding,
                receipts.items().stream().map(row->new OrderReceipt(
                    row.businessNo()==null?row.receiptNo():row.businessNo(),Long.toString(row.amount()),row.currency(),
                    row.disposition(),row.confirmedAt())).toList(),receipts.nextAfter(),
                refunds.items().stream().map(row->new OrderRefund(
                    row.businessNo()==null?row.refundNo():row.businessNo(), Long.toString(row.amount()),
                    row.currency(),row.status(),row.createdAt())).toList(),refunds.nextAfter());
        });
    }

    /** 交易侧返回安全的原生订单证据，可按收款和退款来源独立分页。 */
    public record OrderOperationEvidence(String tradeTenant,String orderId,String orderNo,String businessNo,
            String status,String financialStatus,String currency,Instant createdAt,Instant paidAt,CheckoutPilotAuditLookup.Binding binding,
            List<OrderReceipt> receipts,String receiptNextAfter,List<OrderRefund> refunds,String refundNextAfter) {}

    /** 金额从实际支付回执取得，币种和处置结果一起返回。 */
    public record OrderReceipt(String businessNo,String amountMinor,String currency,String disposition,Instant confirmedAt) {}

    /** 退款实际状态来自原生退款表，使用展示号可继续查看操作轨迹。 */
    public record OrderRefund(String businessNo,String amountMinor,String currency,String status,Instant createdAt) {}

    /** 证据读取不预占资格，不改变首单或资金事实。 */
    @GetMapping("/orders/{orderNo}/referral-evidence")
    public OrderEvidence evidence(@AuthenticationPrincipal Jwt jwt,@PathVariable String orderNo,@RequestParam MultiValueMap<String,String> params) {
        AdminQueryParameters.none(params);evidenceAccess.require();AdminQuery.text(orderNo,64);Actor actor=actor(jwt);
        var header=order.read(actor.tenantId(),orderNo);
        var value=evidence.query(actor.tenantId(),List.of(Long.parseLong(header.orderId()))).getFirst();
        if(value.evidence()==null) throw new TradeException("NOT_FOUND","订单不存在");
        return new OrderEvidence(orderNo,value.evidence());
    }
    /** POST仅承载复杂只读条件，不接收调用方指定的租户或评估结果。 */
    @PostMapping("/members/{memberId}/eligibility:query")
    public CustomerEligibilityService.Result eligibility(@AuthenticationPrincipal Jwt jwt,@PathVariable String memberId,
            @RequestParam MultiValueMap<String,String> params,@Valid @RequestBody EligibilityInput body) {
        AdminQueryParameters.none(params);evidenceAccess.require();
        var result=eligibility.query(actor(jwt).tenantId(),AdminQuery.number(memberId,false),body.policyVersion(),body.campaignStartAt(),body.evaluatedAt());
        if("NOT_FOUND".equals(result.status())) throw new TradeException("NOT_FOUND","会员不存在");return result;
    }
    /** 返回事实字段保持既有证据语义，额外携带运营业务单号。 */
    public record OrderEvidence(String orderNo,@com.fasterxml.jackson.annotation.JsonUnwrapped OrderReferralEvidenceService.Evidence evidence) {}
    /** 资格请求不允许外部主体替代本地会员编号。 */
    public record EligibilityInput(@NotBlank String policyVersion,Instant campaignStartAt,Instant evaluatedAt) {}
    private Actor actor(Jwt jwt) { return new Actor(AdminQuery.number(jwt.getClaimAsString("tenant_id"),false),jwt.getIssuer().toString(),jwt.getSubject()); }
}
