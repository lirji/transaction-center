package com.lrj.trade.core;
import com.lrj.trade.core.mapper.OutboxOperationsMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 受控重放仅恢复原消息，永久凭证、事件、调度和审计同事务提交。 */
@Service
public class OutboxOperationsService {
    private final OutboxOperationsMapper mapper;
    private final OutboxDeliveryPort delivery;
    private final OutboxReplaySchedulePort jobs;
    private final AuditWriter audit;
    /** 持久凭证替代可淘汰的命令缓存，不引入网络或外部审批等待。 */
    public OutboxOperationsService(OutboxOperationsMapper mapper,OutboxDeliveryPort delivery,OutboxReplaySchedulePort jobs,AuditWriter audit) {
        this.mapper=mapper;this.delivery=delivery;this.jobs=jobs;this.audit=audit;
    }
    /** 只返回发布元数据，HTTP层先校验后台权限，租户始终取自已认证主体。 */
    @Transactional(readOnly=true,timeout=5)
    public List<OutboxPublication> page(long tenant,String state,long after,int limit) {
        Checks.positive(tenant);
        if(state==null || !Set.of("PENDING","SENT","REVIEW").contains(state) || after<0 || limit<1 || limit>100)
            throw new TradeException("INVALID_ARGUMENT","发布队列筛选或游标无效");
        return List.copyOf(mapper.page(tenant,state,after,limit));
    }
    /** 调用入口须独立重放权限；相同主体同键永久返回首次结果，不能借新ID绕过去重。 */
    @Transactional(timeout=10)
    public Result replay(Actor actor,String key,String eventId,long expectedVersion,String reason,String ticketRef) {
        Checks.text(key,128);Checks.text(reason,255);Checks.text(ticketRef,128);
        try { if(!UUID.fromString(eventId).toString().equals(eventId)) throw new IllegalArgumentException(); }
        catch(RuntimeException invalid) { throw new TradeException("INVALID_ARGUMENT","事件编号必须为规范UUID"); }
        if(expectedVersion<0) throw new TradeException("INVALID_ARGUMENT","版本必须非负");
        String hash=Checks.hash(eventId,expectedVersion,reason,ticketRef);
        mapper.insertReceipt(actor.tenantId(),actor.key(),key,eventId,hash,reason,ticketRef);
        var receipt=mapper.lockReceipt(actor.tenantId(),actor.key(),key).getFirst();
        if(!receipt.requestHash().equals(hash) || !receipt.eventId().equals(eventId))
            throw new TradeException("IDEMPOTENCY_CONFLICT","重放请求键已经绑定其他内容");
        if(receipt.resultVersion()!=null) return new Result(eventId,receipt.resultVersion(),receipt.resultReplayCount());
        var row=delivery.lock(actor.tenantId(),eventId);
        if(row==null) throw new TradeException("NOT_FOUND","发布事件不存在");
        // 已持有发布行锁，先区分版本过期再判断是否允许重放。
        if(row.version()!=expectedVersion) throw new TradeException("VERSION_CONFLICT","发布版本已变化");
        if(!row.status().equals("REVIEW")) throw new TradeException("STATE_CONFLICT","发布状态不允许重放");
        try { OutboxEventCatalog.validate(row.tenant(),row.eventId(),row.eventType(),row.aggregateId(),row.payload()); }
        catch(RuntimeException invalid) { throw new TradeException("OUTBOX_INVALID_EVENT","事件合同仍然无效，不能重放"); }
        long base=jobs.requeueReviewed(actor.tenantId(),eventId);
        if(base<row.attemptBase()) throw new TradeException("STATE_CONFLICT","任务次数不能倒退");
        if(mapper.replay(actor.tenantId(),eventId,expectedVersion,base)!=1) throw new TradeException("STATE_CONFLICT","重放事件状态已变化");
        long version=Math.addExact(expectedVersion,1),round=Math.addExact(row.replayCount(),1);
        audit.append(actor,"outbox.replay","OUTBOX",eventId,reason,expectedVersion,version);
        if(mapper.finishReceipt(actor.tenantId(),receipt.id(),version,round)!=1) throw new TradeException("STATE_CONFLICT","重放凭证已变化");
        return new Result(eventId,version,round);
    }
    /** 首次重放结果不可随以后重试和发布完成变化。 */
    public record Result(String eventId,long version,long replayCount) {}
}
