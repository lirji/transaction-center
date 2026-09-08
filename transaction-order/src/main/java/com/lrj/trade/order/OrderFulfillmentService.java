package com.lrj.trade.order;

import com.lrj.trade.core.*;
import com.lrj.trade.order.mapper.*;
import com.lrj.trade.order.model.OrderHeader;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;

/** 可信交付与商业完成编排；HTTP入口负责来源授权，会员不能自行声明已履约。 */
@Service
public class OrderFulfillmentService {
    private final OrderMapper orders;
    private final FulfillmentMapper mapper;
    private final Commands commands;
    private final AuditWriter audit;
    private final Outbox outbox;
    private final Clock clock;
    /** 实际物流/服务来源须在调用前验真，本地只维护永久事实和原子订单版本。 */
    public OrderFulfillmentService(OrderMapper orders,FulfillmentMapper mapper,Commands commands,AuditWriter audit,Outbox outbox,Clock clock) {
        this.orders=orders; this.mapper=mapper; this.commands=commands; this.audit=audit; this.outbox=outbox; this.clock=clock;
    }
    /** 确认整单全部冻结明细实际交付；部分发货通知不能调用此内部合同。 */
    public String deliver(Actor source,String event,String orderNo,String evidence,Instant deliveredAt) {
        validate(event,orderNo,evidence); Instant at=instant(deliveredAt);
        String hash=Checks.hash(source.key(),event,orderNo,evidence,at);
        return commands.run(source,"order.delivered",event,hash,()->{
            var order=lock(source.tenantId(),orderNo); Instant now=now();
            if(at.isBefore(order.createdAt()) || at.isAfter(now)) throw invalid();
            if(!Set.of("PAID","FULFILLING","COMPLETED").contains(order.status())
                || (!order.financialStatus().equals("NO_PAYMENT_REQUIRED") && order.paidAt()==null)) throw invalid();
            String number="FF-"+UUID.randomUUID(); long id=Long.parseLong(order.orderId());
            mapper.insertDelivery(source.tenantId(),id,number,source.key(),event,evidence,at,hash,now);
            var found=mapper.lockByOrder(source.tenantId(),id);
            if(found.isEmpty() || !found.getFirst().deliveryHash().equals(hash))
                throw new TradeException("FULFILLMENT_CONFLICT","订单或来源交付事件已经绑定其他内容");
            var row=found.getFirst(); if(!row.fulfillmentNo().equals(number)) return row.fulfillmentNo();
            changed(mapper.markDelivered(source.tenantId(),id,order.version(),now));
            audit.append(source,"order.delivered","ORDER",orderNo,evidence,order.version(),order.version()+1);
            outbox.append(source.tenantId(),"order.delivered.internal.v0",orderNo);
            return number;
        });
    }
    /** 实际交付不等于商业完成；完成证据永久绑定，退款不撤销该历史事实。 */
    public String complete(Actor source,String event,String orderNo,String evidence,Instant completedAt) {
        validate(event,orderNo,evidence); Instant at=instant(completedAt);
        String hash=Checks.hash(source.key(),event,orderNo,evidence,at);
        return commands.run(source,"order.completed",event,hash,()->{
            return completeLocked(source,event,orderNo,evidence,at,hash);
        });
    }
    /** 授权入口先验证本人/实物边界，已存在的商业完成可以返回原事实而不覆盖来源。 */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public String observeCompletion(Actor source,String event,String orderNo,String evidence,Instant completedAt,Long member,boolean physicalOnly) {
        validate(event,orderNo,evidence);Instant at=instant(completedAt);var order=lock(source.tenantId(),orderNo);long id=Long.parseLong(order.orderId());
        if(member!=null && !order.memberId().equals(Long.toString(member))) throw new TradeException("NOT_FOUND","订单不存在");
        if(physicalOnly && orders.items(source.tenantId(),id).stream().anyMatch(item->!item.stockType().equals("PHYSICAL"))) throw invalid();
        var facts=mapper.lockByOrder(source.tenantId(),id);if(facts.isEmpty()) throw invalid();
        if(facts.getFirst().completedAt()!=null) return facts.getFirst().fulfillmentNo();
        return completeLocked(source,event,orderNo,evidence,at,Checks.hash(source.key(),event,orderNo,evidence,at));
    }
    private String completeLocked(Actor source,String event,String orderNo,String evidence,Instant at,String hash) {
            var order=lock(source.tenantId(),orderNo); long id=Long.parseLong(order.orderId());
            var found=mapper.lockByOrder(source.tenantId(),id);
            if(found.isEmpty()) throw invalid();
            var row=found.getFirst();
            if(row.completedAt()!=null) {
                if(!hash.equals(row.completionHash())) throw new TradeException("FULFILLMENT_CONFLICT","商业完成证据已固定，不能覆盖");
                return row.fulfillmentNo();
            }
            Instant now=now(); if(at.isBefore(row.deliveredAt()) || at.isAfter(now)) throw invalid();
            try { changed(mapper.complete(source.tenantId(),id,row.version(),source.key(),event,evidence,at,hash)); }
            catch(org.springframework.dao.DuplicateKeyException conflict) {
                // 来源事件跨订单复用仍是业务冲突；抛出后连同命令与订单改动整事务回滚。
                throw new TradeException("FULFILLMENT_CONFLICT","商业完成来源事件已经绑定其他订单");
            }
            changed(mapper.markCompleted(source.tenantId(),id,order.version(),at,now));
            audit.append(source,"order.completed","ORDER",orderNo,evidence,order.version(),order.version()+1);
            outbox.append(source.tenantId(),"order.completed.internal.v0",orderNo);
            outbox.orderCompleted(OrderCompletedEvent.create(source.tenantId(),new OrderCompletedEvent.Data(
                order.orderId(),order.memberId(),at,order.version()+1)));
            return row.fulfillmentNo();
    }
    private OrderHeader lock(long tenant,String number) {
        return orders.lockByNumber(tenant,number).stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","订单不存在"));
    }
    private void validate(String event,String order,String evidence) { Checks.text(event,128); Checks.text(order,64); Checks.text(evidence,255); }
    private Instant instant(Instant at) { if(at==null) throw invalid(); return at.truncatedTo(ChronoUnit.MILLIS); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private void changed(int rows) { if(rows!=1) throw new TradeException("STATE_CONFLICT","订单履约状态或版本不匹配"); }
    private TradeException invalid() { return new TradeException("FULFILLMENT_INVALID","实际交付或商业完成的状态与时间不满足条件"); }
}
