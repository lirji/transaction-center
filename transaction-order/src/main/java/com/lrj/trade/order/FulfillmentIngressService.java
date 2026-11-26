package com.lrj.trade.order;
import com.lrj.trade.core.*;
import com.lrj.trade.order.mapper.FulfillmentIngressMapper;
import com.lrj.trade.order.model.*;
import com.lrj.trade.member.MemberTradingPort;
import com.lrj.trade.inventory.InventoryReturnPort;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
/** HTTP命令保留窗口和永久来源事件分离，所有回执与领域事实同事务。 */
@Service
public class FulfillmentIngressService {
    private final FulfillmentIngressMapper mapper;private final Commands commands;private final AuditWriter audit;private final OrderFulfillmentService fulfillment;private final OrderReturnService returns;private final MemberTradingPort members;private final InventoryReturnPort inventory;private final Clock clock;
    /** 入口只编排已验证订单/库存端口，不发网络或自行重写回库逻辑。 */
    public FulfillmentIngressService(FulfillmentIngressMapper mapper,Commands commands,AuditWriter audit,OrderFulfillmentService fulfillment,OrderReturnService returns,MemberTradingPort members,InventoryReturnPort inventory,Clock clock) {this.mapper=mapper;this.commands=commands;this.audit=audit;this.fulfillment=fulfillment;this.returns=returns;this.members=members;this.inventory=inventory;this.clock=clock;}
    /** 可信整单实际交付，首次服务端观察时间随永久来源回执冻结。 */
    public Result deliver(Actor actor,String key,String number,String event,String evidence) {
        Checks.text(number,64);Checks.text(evidence,255);
        return run(actor,key,"DELIVER",event,Checks.hash(number,evidence),row->new Result(fulfillment.deliver(actor,event,number,evidence,row.observedAt()),"FULFILLING",0));
    }
    /** 会员只能确认本人已真实交付的全实物订单，不能自报证据或完成时间。 */
    public Result confirm(Actor actor,String key,String number) {
        Checks.text(number,64);
        return run(actor,key,"CONFIRM",number,Checks.hash(number),row->new Result(fulfillment.observeCompletion(actor,"member:"+row.id(),number,"member-confirm:"+number,row.observedAt(),members.memberId(actor),true),"COMPLETED",1));
    }
    /** 可信服务使用JWT租户内订单ID，已完成时复用权威事实。 */
    public Result complete(Actor actor,String key,long orderId,String event,String evidence) {
        Checks.positive(orderId);Checks.text(evidence,255);
        return run(actor,key,"COMPLETE",event,Checks.hash(orderId,evidence),row->{
            String number=mapper.orderNo(actor.tenantId(),orderId).stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","订单不存在"));
            return new Result(fulfillment.observeCompletion(actor,event,number,evidence,row.observedAt(),null,false),"COMPLETED",1);
        });
    }
    /** 退货意图不会占用实收预算或增加库存，验收阶段仍须核对剩余预算。 */
    public Result createReturn(Actor actor,String key,long item,long warehouse,long quantity) {
        Checks.positive(item);Checks.positive(warehouse);Checks.positive(quantity);
        return run(actor,key,"RETURN_CREATE",key,Checks.hash(item,warehouse,quantity),row->{
            var target=mapper.target(actor.tenantId(),item).stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","已交付实物明细不存在"));
            if(!target.stockType().equals("PHYSICAL") || quantity>target.quantity()) throw new TradeException("RETURN_INVALID","申请必须是已交付实物且不超原明细数量");
            inventory.requireWarehouse(actor.tenantId(),warehouse);
            for (int attempt=0; attempt<BusinessNumber.MAX_ISSUE_ATTEMPTS; attempt++) {
                String number=BusinessNumber.next(BusinessNumber.Type.RETURN_REQUEST,row.observedAt());
                try {
                    mapper.createReturn(actor.tenantId(),number,target.orderId(),item,warehouse,quantity,row.observedAt());
                    return new Result(number,"OPEN",0);
                } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                    if (attempt==BusinessNumber.MAX_ISSUE_ATTEMPTS-1 || mapper.lockReturnBusinessNumber(number)==null) throw duplicate;
                }
            }
            throw new IllegalStateException("return number allocation exhausted");
        });
    }
    /** PARTIAL仅指整批实际收到后部分可售，原验收端口负责累计预算与唯一回库。 */
    public Result inspect(Actor actor,String key,String number,long version,String result,long saleable,String evidence) {
        Checks.text(number,64);Checks.text(evidence,255);if(version<0 || saleable<0) throw new TradeException("INVALID_ARGUMENT","验收版本或可售数量无效");
        // 查询号先解析成原申请号，使新旧查询号重放同一份验收回执。
        String originalNo=BusinessNumber.matches(BusinessNumber.Type.RETURN_REQUEST,number)
            ? readReturn(actor.tenantId(),number).returnNo() : number;
        return run(actor,key,"RETURN_INSPECT",originalNo,Checks.hash(originalNo,version,result,saleable,evidence),row->{
            var request=mapper.lockReturn(actor.tenantId(),originalNo).stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","退货申请不存在"));
            if(request.version()!=version) throw new TradeException("PRECONDITION_FAILED","退货申请版本已变化");
            if(!request.status().equals("OPEN")) throw new TradeException("RETURN_CONFLICT","退货申请已验收");
            boolean valid="ACCEPTED".equals(result)?saleable==request.quantity():"PARTIAL".equals(result)?saleable>0 && saleable<request.quantity():"REJECTED".equals(result) && saleable==0;
            if(!valid) throw new TradeException("INVALID_ARGUMENT","验收结论与整批可售数量不一致");
            String receipt=returns.inspect(actor,"rma:"+originalNo,request.orderNo(),Long.parseLong(request.orderItemId()),Long.parseLong(request.warehouseId()),request.quantity(),saleable,evidence);
            if(mapper.finishReturn(actor.tenantId(),originalNo,version,receipt)!=1) throw new TradeException("STATE_CONFLICT","退货申请已变化");
            return new Result(receipt,"INSPECTED",version+1);
        });
    }
    /** 查询仅返回本租户申请状态，不暴露原始证据或产生库存操作。 */
    public ReturnRequest readReturn(long tenant,String number) {Checks.positive(tenant);Checks.text(number,64);return mapper.findReturn(tenant,number).stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","退货申请不存在"));}
    private Result run(Actor actor,String key,String operation,String event,String hash,Function<FulfillmentIngress,Result> action) {
        Checks.text(event,128);String encoded=commands.run(actor,"fulfillment.ingress."+operation,key,Checks.hash(event,hash),()->{
            mapper.insert(actor.tenantId(),actor.key(),operation,event,hash,clock.instant().truncatedTo(ChronoUnit.MILLIS));
            var row=mapper.lock(actor.tenantId(),actor.key(),operation,event);
            if(!row.requestHash().equals(hash)) throw new TradeException("FULFILLMENT_CONFLICT","永久来源事件已绑定其他业务内容");
            if(row.resultNo()!=null) return encode(new Result(row.resultNo(),row.resultStatus(),row.resultVersion()));
            Result result=action.apply(row);
            if(mapper.finish(actor.tenantId(),row.id(),result.resourceNo(),result.status(),result.version())!=1) throw new TradeException("STATE_CONFLICT","来源回执已变化");
            audit.append(actor,"fulfillment.ingress."+operation,"FULFILLMENT_INGRESS",Long.toString(row.id()),"可信入口持久受理",0,1);
            return encode(result);
        });String[] parts=encoded.split("\\|");return new Result(parts[0],parts[1],Long.parseLong(parts[2]));
    }
    private String encode(Result result) {return result.resourceNo()+"|"+result.status()+"|"+result.version();}
    /** 首次结果资源与状态；后续查询资源取得当前状态。 */
    public record Result(String resourceNo,String status,long version) {}
}
