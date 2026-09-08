package com.lrj.trade.order;

import com.lrj.trade.core.*;
import com.lrj.trade.member.MemberTradingPort;
import com.lrj.trade.order.mapper.*;
import com.lrj.trade.order.model.OrderHeader;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 订单所有权与冻结金额的退款适配，沿用订单优先锁序。 */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class OrderRefundService implements OrderRefundPort {
    private final OrderMapper orders;
    private final OrderRefundMapper refunds;
    private final MemberTradingPort members;
    private final Clock clock;
    /** 只使用订单自己的Mapper及会员公开身份端口。 */
    public OrderRefundService(OrderMapper orders,OrderRefundMapper refunds,MemberTradingPort members,Clock clock) {
        this.orders=orders; this.refunds=refunds; this.members=members; this.clock=clock;
    }
    /** 本人查询不要求会员处于有效交易状态，售后不因冻结而消失。 */
    @Override public Snapshot lockOwn(Actor actor,String orderNo) {
        Checks.text(orderNo,64);
        var row=orders.lockOwn(actor.tenantId(),members.memberId(actor),orderNo).stream().findFirst()
            .orElseThrow(()->new TradeException("NOT_FOUND","订单不存在"));
        return snapshot(actor.tenantId(),row);
    }
    /** 后台已有资金恢复不依赖用户是否仍可登录。 */
    @Override public Snapshot lockTrusted(long tenant,long order) { return snapshot(tenant,lock(tenant,order)); }
    /** 退款预占/释放也推进订单证据版本，方便后续消费者查询最新净支付事实。 */
    @Override public Change recordRefundChange(long tenant,long order,long refundedAmount) {
        var row=lock(tenant,order);
        if(refundedAmount<0 || refundedAmount>row.payableAmount() || row.paidAt()==null
            || !Set.of("PAID","PARTIALLY_REFUNDED","REFUNDED").contains(row.financialStatus()))
            throw new TradeException("REFUND_BINDING_CONFLICT","正常退款与订单有效实收不一致");
        String financial=refundedAmount==0?"PAID":refundedAmount==row.payableAmount()?"REFUNDED":"PARTIALLY_REFUNDED";
        if(refunds.updateFinancial(tenant,order,row.version(),financial,clock.instant().truncatedTo(ChronoUnit.MILLIS))!=1)
            throw new TradeException("VERSION_CONFLICT","订单退款证据版本改变");
        return new Change(row.orderId(),row.memberId(),row.version()+1);
    }
    private OrderHeader lock(long tenant,long order) {
        Checks.positive(tenant); Checks.positive(order);
        return orders.lockPaymentOrder(tenant,order).stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","订单不存在"));
    }
    private Snapshot snapshot(long tenant,OrderHeader row) {
        long id=Long.parseLong(row.orderId()); var charges=refunds.charges(tenant,id).getFirst();
        return new Snapshot(row.orderId(),row.orderNo(),row.memberId(),row.payableAmount(),row.currency(),
            charges.freightAmount(),charges.taxAmount(),orders.items(tenant,id).stream()
                .map(i->new Item(i.itemId(),i.quantity(),i.payableAmount())).toList());
    }
}
