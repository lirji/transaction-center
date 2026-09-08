package com.lrj.trade.payment;
import com.lrj.trade.core.*;
import com.lrj.trade.payment.mapper.*;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
/** 原始资金事实导出，RR只覆盖本页，任何读取都不触发查单或收退款。 */
@Service
public class ReconciliationFactsService implements ReconciliationFactsPort {
    private final ReconciliationFactsMapper mapper;
    private final ChannelMapper channels;
    private final Clock clock;
    private final TransactionTemplate tx;
    /** 独立主库短快照防止调用者混入旧事务或副本读取。 */
    public ReconciliationFactsService(ReconciliationFactsMapper mapper,ChannelMapper channels,Clock clock,PlatformTransactionManager transactions) {
        this.mapper=mapper;this.channels=channels;this.clock=clock;
        tx=new TransactionTemplate(transactions);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setReadOnly(true);tx.setTimeout(5);
    }
    /** 冻结渠道仍可核历史；错误或跨租户绑定统一不存在，不泄露其他租户事实。 */
    @Override public Page scan(long tenant,long channelId,long after,Long upper,int scanLimit) {
        Checks.positive(tenant);Checks.positive(channelId);
        if(after<0 || upper!=null && (upper<0 || after>upper) || scanLimit<1 || scanLimit>100)
            throw new TradeException("INVALID_ARGUMENT","资金导出游标或扫描容量无效");
        return tx.execute(status->{
            if(channels.findById(tenant,channelId).size()!=1) throw new TradeException("NOT_FOUND","渠道绑定不存在");
            Long max=upper==null?mapper.upper(tenant):upper;long bound=max==null?0:max;
            if(after>bound) throw new TradeException("INVALID_ARGUMENT","资金导出游标超过上界");
            var candidates=mapper.candidates(tenant,after,bound,scanLimit+1);
            boolean more=candidates.size()>scanLimit;
            var ids=more?candidates.subList(0,scanLimit):candidates;
            long cursor=ids.isEmpty()?after:ids.getLast();
            List<Fact> facts=ids.isEmpty()?List.of():mapper.facts(tenant,channelId,ids);
            return new Page("BOUNDED_SCAN",Long.toString(channelId),Long.toString(bound),Long.toString(cursor),more,ids.size(),clock.instant(),facts);
        });
    }
}
