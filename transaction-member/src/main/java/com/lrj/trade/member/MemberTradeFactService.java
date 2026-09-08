package com.lrj.trade.member;

import com.lrj.trade.core.Checks;
import com.lrj.trade.core.Outbox;
import com.lrj.trade.core.TradeException;
import com.lrj.trade.member.mapper.MemberTradeFactMapper;
import com.lrj.trade.member.model.PaidOrderFact;
import com.lrj.trade.member.model.TradeFact;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 以会员事实行串行化首次确认，业务永久去重与聚合、内部事件同事务提交。 */
@Service
public class MemberTradeFactService implements MemberTradeFactPort {
    private static final String POLICY="FIRST_CONFIRMED_PAID_ORDER_V1";
    private final MemberTradeFactMapper mapper;
    private final Outbox outbox;
    private final Clock clock;
    /** 不注入订单Mapper或渠道SDK；已验真的正常订单确认由上游领域编排负责。 */
    public MemberTradeFactService(MemberTradeFactMapper mapper,Outbox outbox,Clock clock) {
        this.mapper=mapper; this.outbox=outbox; this.clock=clock;
    }
    /** 强制加入上游事务；不能单独提交首单后让收款或库存回滚。 */
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public PaidOrderFact confirmPaidOrder(long tenant,long member,long order,long amount,String currency) {
        Checks.positive(tenant); Checks.positive(member); Checks.positive(order); Checks.positive(amount);
        if(amount>9007199254740991L || currency==null || !currency.matches("[A-Z]{3}"))
            throw new TradeException("INVALID_ARGUMENT","消费金额或币种无效");
        var fact=mapper.lockFact(tenant,member).stream().findFirst()
            .orElseThrow(()->new TradeException("NOT_FOUND","会员消费事实不存在"));
        var old=mapper.lockPaidOrder(tenant,member,order).stream().findFirst();
        if(old.isPresent()) {
            if(old.get().paidAmount()!=amount || !old.get().currency().equals(currency))
                throw new TradeException("PAYMENT_FACT_CONFLICT","订单已确认的消费金额或币种不一致");
            return old.get();
        }
        // 等待事实行锁后才取本地确认时间，绝不根据迟到的渠道时间倒换首单。
        var confirmed=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        long nextVersion=Math.addExact(fact.factVersion(),1);
        boolean first=!fact.everPaid();
        mapper.insertPaidOrder(tenant,member,order,amount,currency,confirmed,first,POLICY,nextVersion);
        if(mapper.advanceFact(tenant,member,order,confirmed,fact.factVersion())!=1)
            throw new TradeException("VERSION_CONFLICT","会员消费事实已改变");
        outbox.append(tenant,"member.paid-order.internal.v0",Long.toString(order));
        return new PaidOrderFact(Long.toString(member),Long.toString(order),amount,currency,confirmed,first,POLICY,nextVersion);
    }
    /** 冻结会员仍保留历史；此方法不解释营销策略，也不承诺查询后仍为新客。 */
    @Override
    public TradeFact read(long tenant,long member) {
        Checks.positive(tenant); Checks.positive(member);
        return mapper.readFact(tenant,member).stream().findFirst()
            .orElseThrow(()->new TradeException("NOT_FOUND","会员消费事实不存在"));
    }
}
