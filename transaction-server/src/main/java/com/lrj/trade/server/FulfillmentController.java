package com.lrj.trade.server;
import com.lrj.trade.core.*;
import com.lrj.trade.order.FulfillmentIngressService;
import com.lrj.trade.order.model.ReturnRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
/** 可信交付、会员确认与独立退货验收的外部合同，不接收客户端租户或会员身份。 */
@RestController
@RequestMapping("")
public class FulfillmentController {
    private final FulfillmentIngressService ingress;private final FulfillmentAccess access;
    /** 来源验真边界与本地事务编排分开，正式WMS仍须外部联调。 */
    public FulfillmentController(FulfillmentIngressService ingress,FulfillmentAccess access) {this.ingress=ingress;this.access=access;}
    /** 操作员明确声明整单实际交付，部分发货不能调用。 */
    @PostMapping("/api/v1/trade/admin/orders/{orderNo}/fulfillments")
    @ResponseStatus(HttpStatus.CREATED)
    public FulfillmentIngressService.Result deliver(@AuthenticationPrincipal Jwt jwt,@PathVariable String orderNo,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Delivery input) {var actor=actor(jwt);access.require(actor,"DELIVER");return ingress.deliver(actor,key,orderNo,input.sourceRequestId(),input.deliveryRef());}
    /** 本人只能确认已有全实物交付，服务完成必须走可信服务入口。 */
    @PostMapping("/api/v1/trade/orders/{orderNo}:confirm-receipt")
    public FulfillmentIngressService.Result confirm(@AuthenticationPrincipal Jwt jwt,@PathVariable String orderNo,@RequestHeader("Idempotency-Key") String key) {access.memberEnabled();return ingress.confirm(actor(jwt),key,orderNo);}
    /** 服务身份和租户来自已验签令牌，完成时间由永久入口回执冻结。 */
    @PostMapping("/internal/v1/orders/{orderId}:complete")
    public FulfillmentIngressService.Result complete(@AuthenticationPrincipal Jwt jwt,@PathVariable String orderId,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Completion input) {var actor=actor(jwt);access.require(actor,"COMPLETE");return ingress.complete(actor,key,id(orderId),input.sourceRequestId(),input.deliveryEvidenceRef());}
    /** 创建意图不变库存，申请数量是本批预计实际接收数量。 */
    @PostMapping("/api/v1/trade/admin/returns")
    @ResponseStatus(HttpStatus.CREATED)
    public FulfillmentIngressService.Result createReturn(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ReturnInput input) {var actor=actor(jwt);access.require(actor,"RETURN");return ingress.createReturn(actor,key,id(input.orderItemId()),id(input.warehouseId()),input.quantity());}
    /** 整批已收到才能验收，PARTIAL仅表示部分可售。 */
    @PostMapping("/api/v1/trade/admin/returns/{returnNo}:inspect")
    public InspectionResult inspect(@AuthenticationPrincipal Jwt jwt,@PathVariable String returnNo,@RequestHeader("Idempotency-Key") String key,@RequestHeader(value="If-Match",required=false) String match,@Valid @RequestBody Inspection input) {var actor=actor(jwt);access.require(actor,"RETURN");var value=ingress.inspect(actor,key,returnNo,version(match),input.result(),input.restockQuantity(),input.evidenceRef());return new InspectionResult(returnNo,value.resourceNo(),value.status(),value.version());}
    private Actor actor(Jwt jwt) {try {return new Actor(Long.parseLong(jwt.getClaimAsString("tenant_id")),jwt.getIssuer().toString(),jwt.getSubject());}catch(RuntimeException invalid) {throw new TradeException("INVALID_IDENTITY","令牌缺少有效身份");}}
    private long id(String text) {try {if(text==null || !text.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();long value=Long.parseLong(text);Checks.positive(value);return value;}catch(NumberFormatException invalid){throw new TradeException("INVALID_ARGUMENT","资源ID无效");}}
    private long version(String match) {return AdminVersion.parse(match); }
    /** 来源事件永久绑定整单实际交付引用。 */
    public record Delivery(@NotBlank @Size(max=128) String sourceRequestId,@NotBlank @Size(max=255) String deliveryRef) {}
    /** 完成证据与交付证据分开，不能省略已交付事实。 */
    public record Completion(@NotBlank @Size(max=128) String sourceRequestId,@NotBlank @Size(max=255) String deliveryEvidenceRef) {}
    /** 实物退货申请，不接收客户端自报订单归属。 */
    public record ReturnInput(@NotBlank String orderItemId,@NotBlank String warehouseId,@NotNull @Positive Long quantity) {}
    /** 申请版本与独立验收事实编号明确区分，避免把库存事实号用于申请更新。 */
    public record InspectionResult(String returnNo,String receiptNo,String status,long version) {}
    /** 可售数量必须与整批验收分类严格一致。 */
    public record Inspection(@NotBlank @Pattern(regexp="ACCEPTED|PARTIAL|REJECTED") String result,@NotNull @PositiveOrZero Long restockQuantity,@NotBlank @Size(max=255) String evidenceRef) {}
}
