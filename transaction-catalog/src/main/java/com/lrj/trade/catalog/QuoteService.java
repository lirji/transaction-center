package com.lrj.trade.catalog;

import com.lrj.trade.core.*;
import com.lrj.trade.member.MemberTradingPort;
import com.lrj.trade.catalog.mapper.QuoteMapper;
import com.lrj.trade.catalog.model.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 服务端报价是不可变事实，幂等重试不能重新定价或延长有效期。 */
@Service
public class QuoteService implements QuotePort {
    private final QuoteMapper mapper;
    private final Commands commands;
    private final AuditWriter audit;
    private final MemberTradingPort members;
    private final SkuService skus;
    private final PricingPolicy pricing;
    private final Clock clock;
    private final long ttlSeconds;
    /** 复用可控制时钟和定价策略，有效期配置错误必须在启动时失败。 */
    public QuoteService(QuoteMapper mapper,Commands commands,AuditWriter audit,MemberTradingPort members,SkuService skus,
                        PricingPolicy pricing,Clock clock,@Value("${trade.quote.ttl-seconds:300}") long ttlSeconds) {
        if(ttlSeconds<1 || ttlSeconds>900) throw new IllegalArgumentException("报价有效期必须在1至900秒");
        this.mapper=mapper; this.commands=commands; this.audit=audit; this.members=members; this.skus=skus;
        this.pricing=pricing; this.clock=clock; this.ttlSeconds=ttlSeconds;
    }
    /** 同键同一购物清单返回原快照，换序不产生新报价；金额始终从发布版本计算。 */
    public Quote create(Actor actor,String key,List<QuoteSelection> selections) {
        SortedMap<Long,Long> items=normalize(selections);
        String id=commands.run(actor,"quote.create",key,Checks.hash(items.toString()),()->{
            long member=members.requireActive(actor);
            List<QuoteItem> priced=new ArrayList<>();
            String currency=null;
            long total=0;
            for(var item:items.entrySet()) {
                Sku sku=skus.published(actor.tenantId(),item.getKey());
                if(currency!=null && !currency.equals(sku.currency()))
                    throw new TradeException("INVALID_ARGUMENT","同一报价不能混合币种");
                currency=sku.currency();
                QuoteItem line=pricing.price(sku,item.getValue());
                try {
                    total=Math.addExact(total,line.payableAmount());
                    if(total>9007199254740991L) throw new ArithmeticException();
                } catch(ArithmeticException overflow) {
                    throw new TradeException("INVALID_ARGUMENT","报价总额超出安全整数范围");
                }
                priced.add(line);
            }
            // 取得全部锁后再定有效期，避免锁等待耗尽刚生成的报价有效时间。
            var created=clock.instant().truncatedTo(ChronoUnit.MILLIS);
            mapper.insertQuote(actor.tenantId(),member,total,currency,pricing.version(),created,created.plusSeconds(ttlSeconds));
            // 紧跟INSERT且处于同一连接事务，LAST_INSERT_ID不会被其他请求或后续明细写入覆盖。
            long quote=mapper.lastInsertedQuoteId(actor.tenantId());
            for(QuoteItem line:priced) mapper.insertItem(actor.tenantId(),quote,Long.parseLong(line.skuId()),line.skuRevision(),
                line.title(),Specifications.encode(line.specification()),line.stockType(),line.quantity(),line.unitPrice(),
                line.payableAmount(),created);
            audit.append(actor,"quote.create","QUOTE",Long.toString(quote),"创建不可变服务端报价",0,0);
            return Long.toString(quote);
        });
        return read(actor,Long.parseLong(id));
    }
    /** 历史报价始终可以由本人查询；冻结或过期不改写过去价格，也不延长有效期。 */
    public Quote read(Actor actor,long quoteId) {
        Checks.positive(quoteId);
        return load(actor.tenantId(),members.memberId(actor),quoteId);
    }
    /** 有效旧报价仍锁价，但商品当前必须可售；失效边界在全部锁等待后再次判断。 */
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public Quote requireUsable(Actor actor,long quoteId) {
        Checks.positive(quoteId);
        long member=members.requireActive(actor);
        Quote quote=load(actor.tenantId(),member,quoteId);
        for(QuoteItem item:quote.items()) skus.published(actor.tenantId(),Long.parseLong(item.skuId()));
        if(!clock.instant().isBefore(quote.expiresAt()))
            throw new TradeException("QUOTE_EXPIRED","报价已过期，请重新获取报价");
        return quote;
    }
    private Quote load(long tenant,long member,long quoteId) {
        QuoteHeader header=mapper.findOwn(tenant,member,quoteId).stream().findFirst()
            .orElseThrow(()->new TradeException("NOT_FOUND","报价不存在"));
        return new Quote(header.quoteId(),header.payableAmount(),header.currency(),header.pricingPolicy(),
            header.createdAt(),header.expiresAt(),List.copyOf(mapper.listItems(tenant,quoteId)));
    }
    private SortedMap<Long,Long> normalize(List<QuoteSelection> selections) {
        if(selections==null || selections.isEmpty() || selections.size()>50)
            throw new TradeException("INVALID_ARGUMENT","报价必须包含1至50个不同SKU");
        SortedMap<Long,Long> items=new TreeMap<>();
        for(QuoteSelection selection:selections) {
            if(selection==null || selection.skuId()==null || !selection.skuId().matches("[1-9][0-9]{0,18}")
                || selection.quantity()==null || selection.quantity()<1 || selection.quantity()>1_000_000_000L)
                throw new TradeException("INVALID_ARGUMENT","SKU或购买数量无效");
            long sku;
            try { sku=Long.parseLong(selection.skuId()); }
            catch(NumberFormatException invalid) { throw new TradeException("INVALID_ARGUMENT","SKU编号超限"); }
            if(items.putIfAbsent(sku,selection.quantity())!=null)
                throw new TradeException("INVALID_ARGUMENT","报价不允许重复SKU");
        }
        return items;
    }
}
