package com.lrj.trade.order;
import com.lrj.trade.core.*;
import com.lrj.trade.order.mapper.*;
import com.lrj.trade.inventory.InventoryReturnPort;
import com.lrj.trade.order.model.ReturnReceipt;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
/** 可信仓储验收核心，与退款独立；授权仓储HTTP入口通过来源校验后调用。 */
@Service
public class OrderReturnService {
    private final OrderMapper orders;
    private final FulfillmentMapper fulfillment;
    private final ReturnReceiptMapper mapper;
    private final InventoryReturnPort inventory;
    private final Commands commands;
    private final AuditWriter audit;
    private final Outbox outbox;
    private final Clock clock;
    /** 订单事实、数量预算、库存及事件通过同数据源事务提交。 */
    public OrderReturnService(OrderMapper orders,FulfillmentMapper fulfillment,ReturnReceiptMapper mapper,InventoryReturnPort inventory,Commands commands,AuditWriter audit,Outbox outbox,Clock clock) {
        this.orders=orders; this.fulfillment=fulfillment; this.mapper=mapper; this.inventory=inventory; this.commands=commands; this.audit=audit; this.outbox=outbox; this.clock=clock;
    }
    /** 记录已经完成检验的实物接收，部分可售或全部不合格均永久占用实收预算。 */
    public String inspect(Actor source,String event,String orderNo,long itemId,long warehouse,long received,long saleable,String evidence) {
        Checks.text(event,128); Checks.text(orderNo,64); Checks.text(evidence,255); Checks.positive(itemId); Checks.positive(warehouse); Checks.positive(received);
        if(saleable<0 || saleable>received) throw new TradeException("INVALID_ARGUMENT","可售数量必须在实收数量以内");
        String hash=Checks.hash(source.key(),event,orderNo,itemId,warehouse,received,saleable,evidence);
        return commands.run(source,"order.return.inspect",event,hash,()->{
            var order=orders.lockByNumber(source.tenantId(),orderNo).stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","订单不存在"));
            long id=Long.parseLong(order.orderId());
            if(fulfillment.lockByOrder(source.tenantId(),id).isEmpty()) throw invalid();
            var item=orders.items(source.tenantId(),id).stream().filter(i->i.itemId().equals(Long.toString(itemId))).findFirst().orElseThrow(this::invalid);
            if(!item.stockType().equals("PHYSICAL") || item.reservationKey()==null) throw invalid();
            inventory.requireWarehouse(source.tenantId(),warehouse);
            String number="RT-"+UUID.randomUUID();
            var createdAt=clock.instant().truncatedTo(ChronoUnit.MILLIS);
            ReturnReceipt found=null;
            for (int attempt=0; attempt<BusinessNumber.MAX_ISSUE_ATTEMPTS; attempt++) {
                String businessNo=BusinessNumber.next(BusinessNumber.Type.RETURN_RECEIPT,createdAt);
                mapper.insert(source.tenantId(),number,businessNo,id,itemId,source.key(),event,
                    warehouse,received,saleable,evidence,hash,createdAt);
                found=mapper.lockEvent(source.tenantId(),source.key(),event);
                // 原验收号继续作为回库关联；只为不存在的来源事件处理候选号碰撞。
                if (found!=null || mapper.lockBusinessNumber(businessNo)==null) break;
            }
            if(found==null || !found.requestHash().equals(hash)) throw new TradeException("RETURN_CONFLICT","验收来源事件已经绑定其他内容");
            if(!found.returnNo().equals(number)) return found.returnNo();
            if(mapper.consumeBudget(source.tenantId(),id,itemId,received)!=1) throw invalid();
            if(saleable>0) inventory.restock(source,number,item.reservationKey(),Long.parseLong(item.skuId()),item.quantity(),warehouse,saleable);
            audit.append(source,"order.return.inspect","RETURN",number,evidence,0,1);
            outbox.append(source.tenantId(),"order.return.inspected.internal.v0",number);
            return number;
        });
    }
    private TradeException invalid() { return new TradeException("RETURN_INVALID","实物退货必须有实际交付且累计实收不能超过原明细数量"); }
}
