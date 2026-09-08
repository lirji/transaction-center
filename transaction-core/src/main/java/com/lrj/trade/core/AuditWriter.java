package com.lrj.trade.core;

import com.lrj.trade.core.mapper.AuditMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 审计写入必须加入业务事务，不能在业务失败后留下成功操作的假记录。 */
@Component
public class AuditWriter {
    private final AuditMapper mapper;
    /** 复用本地事务数据源；审计表不承担跨系统消息投递职责。 */
    public AuditWriter(AuditMapper mapper) { this.mapper=mapper; }
    /** 只保留动作、资源、原因及版本，不复制会员资料或认证令牌。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void append(Actor actor,String action,String type,String resource,String reason,long before,long after) {
        Checks.text(action,64); Checks.text(type,64); Checks.text(resource,64); Checks.text(reason,255);
        mapper.insertAudit(actor.tenantId(),actor.key(),action,type,resource,reason,before,after);
    }
}
