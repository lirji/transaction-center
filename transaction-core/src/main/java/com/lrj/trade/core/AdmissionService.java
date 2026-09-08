package com.lrj.trade.core;
import com.lrj.trade.core.mapper.AdmissionMapper;
import com.lrj.trade.core.mapper.AdmissionTransactionGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 持久租户准入策略，只约束新增受理；退款、回调和已受理任务继续恢复。 */
@Service
public class AdmissionService {
    private final AutomaticAdmissionGate automatic;private final AdmissionMapper mapper;private final AdmissionTransactionGuard guard;private final Commands commands;private final AuditWriter audit;
    /** 复用命令幂等和原子审计，过期配置由版本条件拒绝。 */
    public AdmissionService(AdmissionMapper mapper,AdmissionTransactionGuard guard,Commands commands,AuditWriter audit,AutomaticAdmissionGate automatic) {this.automatic=automatic;this.mapper=mapper;this.guard=guard;this.commands=commands;this.audit=audit;}
    /** 缺省租户允许新增，读接口不写入策略。 */
    public AdmissionPolicy read(long tenant) {Checks.positive(tenant);return mapper.find(tenant).stream().findFirst().orElse(new AdmissionPolicy(true,true,0));}
    /** 必须早于任何领域锁取得共享锁，并保持至调用事务结束。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public AdmissionPolicy acquire(long tenant) {Checks.positive(tenant);guard.requireRepeatableRead();return automatic.apply(mapper.share(tenant).stream().findFirst().orElse(new AdmissionPolicy(true,true,0)));}
    /** 管理命令只锁策略；首次结果编码保留原版本和两个开关，不读取后来的配置冒充原响应。 */
    public AdmissionPolicy update(Actor actor,String key,long version,boolean orders,boolean payments,String reason) {
        if(version<0 || version==Long.MAX_VALUE) throw new TradeException("INVALID_ARGUMENT","策略版本无效");Checks.text(reason,255);
        String result=commands.run(actor,"admission.update",key,Checks.hash(version,orders,payments,reason),()->{
            mapper.ensure(actor.tenantId());var current=mapper.lock(actor.tenantId());
            if(current.version()!=version || mapper.update(actor.tenantId(),version,orders,payments)!=1) throw new TradeException("PRECONDITION_FAILED","准入策略已更新，请重新读取版本");
            audit.append(actor,"admission.update","ADMISSION_POLICY",Long.toString(actor.tenantId()),reason,version,version+1);
            return (version+1)+":"+orders+":"+payments;
        });
        String[] parts=result.split(":");return new AdmissionPolicy(Boolean.parseBoolean(parts[1]),Boolean.parseBoolean(parts[2]),Long.parseLong(parts[0]));
    }
}
