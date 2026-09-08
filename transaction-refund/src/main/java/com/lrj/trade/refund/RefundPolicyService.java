package com.lrj.trade.refund;

import com.lrj.trade.core.*;
import com.lrj.trade.refund.mapper.RefundMapper;
import com.lrj.trade.refund.model.RefundPolicy;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 租户关系库策略，默认必须审批；变更需后台授权、幂等、版本与同事务审计。 */
@Service
public class RefundPolicyService implements RefundPolicyPort {
    private final RefundMapper mapper;
    private final Commands commands;
    private final AuditWriter audit;
    private final Clock clock;
    /** 配置与业务数据复用交易主库，不另建内存正式策略。 */
    public RefundPolicyService(RefundMapper mapper,Commands commands,AuditWriter audit,Clock clock) {
        this.mapper=mapper; this.commands=commands; this.audit=audit; this.clock=clock;
    }
    /** 仅经后台授权的入口调用；期望版本阻止覆盖并发配置，零门槛代表必须审批。 */
    public RefundPolicy configure(Actor actor,String key,String currency,long limit,long expectedVersion) {
        if(currency==null || !currency.matches("[A-Z]{3}") || limit<0 || limit>9007199254740991L || expectedVersion<0)
            throw new TradeException("INVALID_ARGUMENT","退款政策金额币种或版本无效");
        String appliedVersion=commands.run(actor,"refund.policy",key,Checks.hash(currency,limit,expectedVersion),()->{
            var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
            mapper.initializePolicy(actor.tenantId(),currency,now);
            var current=mapper.lockPolicy(actor.tenantId()).getFirst();
            if(current.version()!=expectedVersion) throw new TradeException("VERSION_CONFLICT","退款政策版本改变");
            if(mapper.updatePolicy(actor.tenantId(),currency,limit,expectedVersion,now)!=1)
                throw new TradeException("VERSION_CONFLICT","退款政策并发变更");
            audit.append(actor,"refund.policy","REFUND_POLICY",Long.toString(actor.tenantId()),currency+":"+limit,expectedVersion,expectedVersion+1);
            return Long.toString(expectedVersion+1);
        });
        // 幂等重放返回该命令原先应用的版本，不能被后来其他管理员的配置覆盖响应。
        return new RefundPolicy(currency,limit,Long.parseLong(appliedVersion));
    }
    /** 未配置时明确返回零版本，不能当成可免审的隐式默认值。 */
    public RefundPolicy read(long tenant) {
        Checks.positive(tenant); return mapper.findPolicy(tenant).stream().findFirst().orElse(new RefundPolicy("CNY",0,0));
    }
    /** 只读本地已配置快照；异常及币种不匹配均强制审批。 */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Decision evaluate(long tenant,String currency,long cumulativeAmount,boolean abnormal) {
        var policy=read(tenant);
        boolean approve=!abnormal && policy.version()>0 && policy.currency().equals(currency)
            && cumulativeAmount>0 && cumulativeAmount<=policy.autoApproveLimit();
        return new Decision(!approve,policy.version(),policy.autoApproveLimit());
    }
}
