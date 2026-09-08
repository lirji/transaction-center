package com.lrj.trade.order;

import com.lrj.trade.core.*;
import com.lrj.trade.order.mapper.MerchantMapper;
import com.lrj.trade.order.model.Merchant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 显式租户商户配置，禁止使用默认商户或客户端下单参数决定收款主体。 */
@Service
public class MerchantService implements MerchantTradingPort {
    private final MerchantMapper mapper;
    private final Commands commands;
    private final AuditWriter audit;
    /** 配置操作与命令及审计同事务提交。 */
    public MerchantService(MerchantMapper mapper,Commands commands,AuditWriter audit) {
        this.mapper=mapper; this.commands=commands; this.audit=audit;
    }
    /** 创建不可变逻辑商户编号，实际渠道映射须后续单独配置和认证。 */
    public String create(Actor actor,String key,String number,String name) {
        Checks.text(number,64); Checks.text(name,200);
        return commands.run(actor,"merchant.create",key,Checks.hash(number,name),()->{
            mapper.insert(actor.tenantId(),number,name);
            String id=read(actor.tenantId()).merchantId();
            audit.append(actor,"merchant.create","MERCHANT",id,"配置租户逻辑商户",0,0);
            return id;
        });
    }
    /** 管理端只读取当前租户配置，缺少配置明确报错。 */
    public Merchant read(long tenant) {
        Checks.positive(tenant);
        return mapper.find(tenant).stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","商户尚未配置"));
    }
    /** 冻结禁止新订单，不能把历史支付或退款事实删除。 */
    public String changeStatus(Actor actor,String key,long version,String status,String reason) {
        if(version<0 || !("ACTIVE".equals(status)||"FROZEN".equals(status)))
            throw new TradeException("INVALID_ARGUMENT","商户状态或版本无效");
        Checks.text(reason,255);
        return commands.run(actor,"merchant.status",key,Checks.hash(version,status,reason),()->{
            if(mapper.changeStatus(actor.tenantId(),version,status)!=1)
                throw new TradeException("VERSION_CONFLICT","商户不存在、版本过期或状态未改变");
            String id=read(actor.tenantId()).merchantId();
            audit.append(actor,"merchant.status."+status,"MERCHANT",id,reason,version,version+1);
            return id;
        });
    }
    /** 必须在订单事务内共享锁定；缺少或冻结配置时禁止新下单。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public Merchant requireActive(long tenant) {
        return mapper.lockActive(tenant).stream().findFirst()
            .orElseThrow(()->new TradeException("MERCHANT_UNAVAILABLE","商户未配置或已冻结"));
    }
}
