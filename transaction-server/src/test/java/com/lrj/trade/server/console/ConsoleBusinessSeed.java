package com.lrj.trade.server.console;

import com.lrj.trade.core.Actor;
import com.lrj.trade.catalog.*;
import com.lrj.trade.catalog.model.QuoteSelection;
import com.lrj.trade.inventory.InventoryService;
import com.lrj.trade.member.MemberService;
import com.lrj.trade.order.*;
import com.lrj.trade.order.model.*;
import com.lrj.trade.payment.*;
import com.lrj.trade.refund.*;
import com.lrj.trade.refund.model.*;
import java.time.Instant;
import java.util.*;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 测试classpath专用业务种子；仅通过生产领域服务写入，不SQL改资金/库存状态。 */
public final class ConsoleBusinessSeed {
    private final ApplicationContext app;
    private final Actor member,operator,approver;
    private final TransactionTemplate tx;
    private final Map<String,String> data=new LinkedHashMap<>();
    /** 调用者须提供独立测试库；签发方与浏览器JWT保持一致。 */
    public ConsoleBusinessSeed(ApplicationContext app,String issuer) {
        this.app=app;member=new Actor(900001,issuer,"member");operator=new Actor(900001,issuer,"admin");approver=new Actor(900001,issuer,"approver");
        tx=new TransactionTemplate(bean(PlatformTransactionManager.class));
    }
    private <T> T bean(Class<T> type) { return app.getBean(type); }
    /** 稳定业务键可重复执行；返回仅含业务号的清单，供真实页面定位数据库记录。 */
    public Map<String,String> seed() {
        bean(MemberService.class).register(member,"联调会员");
        data.put("memberId",bean(MemberService.class).me(member).memberId());
        bean(MerchantService.class).create(operator,"seed-merchant","INTEGRATION","隔离联调商户");
        String product=bean(CatalogService.class).create(operator,"seed-product","INTEGRATION-P","联调商品","INTEGRATION-S",10000,"CNY");
        bean(CatalogService.class).publish(operator,"seed-publish",Long.parseLong(product),0);
        String sku=bean(CatalogService.class).list(900001,0,100,true).stream().filter(p->p.productId().equals(product)).findFirst().orElseThrow().skuId();
        long warehouse=Long.parseLong(bean(InventoryService.class).warehouse(operator,"seed-warehouse","INTEGRATION-W","联调仓库"));
        bean(InventoryService.class).adjust(operator,"seed-stock",warehouse,Long.parseLong(sku),100,"联调种子库存");
        data.put("warehouseId",Long.toString(warehouse));data.put("skuId",sku);
        bean(ChannelConfigurationService.class).create(operator,"seed-channel","INTEGRATION","isolated-merchant","CNY","test:isolated","return","https://shop.test/return");
        OrderView pending=order("pending",sku,warehouse);data.put("pendingOrder",pending.orderNo());
        var pendingPayment=bean(PaymentService.class).create(member,"seed-payment-pending",pending.orderNo(),"INTEGRATION","return");
        data.put("pendingPayment",pendingPayment.paymentNo());
        OrderView cancel=order("cancel",sku,warehouse);bean(OrderService.class).cancel(member,"seed-cancel",cancel.orderNo(),"联调取消");data.put("cancelledOrder",cancel.orderNo());
        OrderView paid=order("paid",sku,warehouse);
        var payment=bean(PaymentService.class).create(member,"seed-payment",paid.orderNo(),"INTEGRATION","return");
        // 显式测试渠道输入模拟已验真结果，生产签名入口/供应商联调不由本种子冒充。
        var receipt=bean(PaymentObservationService.class).received(900001,payment.activeAttemptNo(),"integration-paid",paid.payableAmount(),"CNY",Instant.parse("2026-01-01T00:00:00Z"),"test:verified-payment");
        var abnormal=bean(PaymentObservationService.class).received(900001,payment.activeAttemptNo(),"integration-extra",100,"CNY",Instant.parse("2026-01-01T00:00:00Z"),"test:verified-extra");
        data.put("paidOrder",paid.orderNo());data.put("paymentNo",payment.paymentNo());data.put("receiptNo",receipt.receiptNo());data.put("abnormalReceiptNo",abnormal.receiptNo());
        data.put("quoteId",paid.quoteId());
        var refundService=bean(RefundService.class);
        RefundView waiting=refundService.request(member,"seed-refund-pending",paid.orderNo(),1000,selection(paid,1000),"联调待审批");
        data.put("refundNo",waiting.refundNo());
        RefundView rejected=refundService.request(member,"seed-refund-rejected",paid.orderNo(),1000,selection(paid,1000),"联调拒绝");
        tx.executeWithoutResult(s->refundService.applyApproval(approver,rejected.refundNo(),rejected.approvalRef(),1,false,"test:trusted-approval"));
        RefundView success=refundService.request(member,"seed-refund-success",paid.orderNo(),1000,selection(paid,1000),"联调成功");
        tx.executeWithoutResult(s->refundService.applyApproval(approver,success.refundNo(),success.approvalRef(),1,true,"test:trusted-approval"));
        // 内部端口只接受已验真事实；测试不开放HTTP假审批/假资金成功入口。
        if(!refundService.read(member,success.refundNo()).status().equals("SUCCEEDED")) {
            tx.executeWithoutResult(s->refundService.prepare(900001,success.refundNo()));
            tx.executeWithoutResult(s->refundService.confirmSucceeded(900001,success.refundNo(),"integration-refunded",1000,"CNY",Instant.now(),"test:verified-refund"));
        }
        data.put("successfulRefund",success.refundNo());
        RefundView submitted=refundService.request(member,"seed-refund-submitted",paid.orderNo(),1000,selection(paid,1000),"联调处理中");
        tx.executeWithoutResult(s->refundService.applyApproval(approver,submitted.refundNo(),submitted.approvalRef(),1,true,"test:trusted-approval"));
        tx.executeWithoutResult(s->refundService.prepare(900001,submitted.refundNo()));
        data.put("submittedRefund",submitted.refundNo());
        var ingress=bean(FulfillmentIngressService.class);
        ingress.deliver(operator,"seed-deliver",paid.orderNo(),"integration-delivery","test:warehouse-delivery");
        ingress.confirm(member,"seed-confirm",paid.orderNo());
        var returned=ingress.createReturn(operator,"seed-return-inspected",Long.parseLong(paid.items().getFirst().itemId()),warehouse,1);
        ingress.inspect(operator,"seed-inspect",returned.resourceNo(),0,"ACCEPTED",1,"test:inspection");
        data.put("inspectedReturn",returned.resourceNo());
        var open=ingress.createReturn(operator,"seed-return-open",Long.parseLong(paid.items().getFirst().itemId()),warehouse,1);
        data.put("openReturn",open.resourceNo());
        return Map.copyOf(data);
    }
    private List<RefundSelection> selection(OrderView order,long amount) {return List.of(new RefundSelection("GOODS",order.items().getFirst().itemId(),0,amount));}
    private OrderView order(String key,String sku,long warehouse) {
        var quote=bean(QuoteService.class).create(member,"seed-quote-"+key,List.of(new QuoteSelection(sku,2L)));
        return bean(OrderService.class).create(member,"seed-order-"+key,Long.parseLong(quote.quoteId()),warehouse,new ShippingAddress("联调收件人","13800138000","隔离测试地址"));
    }
}
