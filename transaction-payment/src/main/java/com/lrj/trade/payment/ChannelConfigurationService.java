package com.lrj.trade.payment;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
import com.lrj.trade.core.query.AdminNumberedPage;
import com.lrj.trade.core.*;
import com.lrj.trade.order.MerchantTradingPort;
import com.lrj.trade.payment.mapper.ChannelMapper;
import com.lrj.trade.payment.model.*;
import java.net.URI;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;

/** 配置不触发真实渠道调用；收款主体和回跳地址由管理员登记，不能从支付请求接收。 */
@Service
public class ChannelConfigurationService {
    private final ChannelMapper mapper;
    private final MerchantTradingPort merchants;
    private final Commands commands;
    private final AuditWriter audit;
    private final Clock clock;
    /** 使用同一快照读取准确条数和页内记录，沿用当前租户与允许的业务筛选。 */
    @Transactional(readOnly = true, timeout = 3, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public AdminNumberedPage<ChannelView> numberedChannels(long tenant, int limit, long page) {
        Checks.positive(tenant);
        return AdminNumberedPage.of(page, limit, mapper.listCount(tenant), clock.instant(),
                offset -> mapper.listAtOffset(tenant, limit, offset).stream()
                        .map(ChannelConfigurationService::safeView).toList());
    }

    /** 渠道绑定与审计共用本地事务，引用所属商户Port。 */
    public ChannelConfigurationService(ChannelMapper mapper,MerchantTradingPort merchants,Commands commands,AuditWriter audit,Clock clock) {
        this.mapper=mapper; this.merchants=merchants; this.commands=commands; this.audit=audit; this.clock=clock;
    }
    /** 绑定首期单币种收款主体；配置内容不可覆盖，后续密钥版本迁移须显式设计。 */
    public String create(Actor actor,String key,String code,String channelMerchant,String currency,String credential,String returnId,String returnUrl) {
        Checks.text(code,64); Checks.text(channelMerchant,255); Checks.text(returnId,64); Checks.text(returnUrl,1000);
        if(!code.matches("[A-Z][A-Z0-9_]{0,63}") || currency==null || !currency.matches("[A-Z]{3}")
            || credential==null || !credential.matches("[A-Za-z0-9][A-Za-z0-9/_:.-]{0,254}"))
            throw new TradeException("INVALID_ARGUMENT","渠道代码、币种或托管引用无效");
        try {
            URI uri=URI.create(returnUrl);
            if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null)
                throw new IllegalArgumentException();
        } catch(IllegalArgumentException invalid) { throw new TradeException("INVALID_ARGUMENT","回跳地址必须为预登记HTTPS地址"); }
        return commands.run(actor,"payment.channel.create",key,Checks.hash(code,channelMerchant,currency,credential,returnId,returnUrl),()->{
            var merchant=merchants.requireActive(actor.tenantId());
            mapper.insert(actor.tenantId(),merchant.merchantNo(),code,channelMerchant,currency,credential,returnId,returnUrl,clock.instant().truncatedTo(ChronoUnit.MILLIS));
            var config=mapper.lockActive(actor.tenantId(),merchant.merchantNo(),code,currency).getFirst();
            audit.append(actor,"payment.channel.create","PAYMENT_CHANNEL",config.channelId(),"登记不可变渠道绑定",0,0);
            return config.channelId();
        });
    }
    /** 管理员只能看到当前租户摘要，不返回密钥托管引用。 */
    public List<ChannelView> list(long tenant,long after,int limit) {
        Checks.positive(tenant);
        if(after<0 || limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","分页参数无效");
        return mapper.list(tenant,after,limit).stream().map(ChannelConfigurationService::safeView).toList();
    }

    private static ChannelView safeView(ChannelConfiguration c) {
        return new ChannelView(c.channelId(), c.merchantNo(), c.channelCode(), c.channelMerchantNo(),
                c.currency(), c.returnUrlId(), c.status(), c.version());
    }
}
