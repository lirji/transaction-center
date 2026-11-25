package com.lrj.trade.order;

import com.lrj.trade.core.*;
import com.lrj.trade.catalog.QuotePort;
import com.lrj.trade.catalog.model.CheckoutPrice;
import com.lrj.trade.catalog.model.Quote;
import com.lrj.trade.member.MemberTradingPort;
import com.lrj.trade.member.MemberTradeFactPort;
import com.lrj.trade.inventory.InventoryTradingPort;
import com.lrj.trade.order.mapper.OrderMapper;
import com.lrj.trade.order.model.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 订单、明细、地址密文、库存及事件共用本地事务，不能留下部分成功订单。 */
@Service
public class OrderService implements OrderPaymentPort {
    public static final String CHECKOUT_FUNDING_PENDING = "CHECKOUT_PILOT_FUNDING_PENDING";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final OrderMapper mapper;
    private final com.lrj.trade.order.mapper.OrderAttributionMapper attributions;
    private final org.springframework.beans.factory.ObjectProvider<OrderAttributionVerifierPort> attributionVerifier;
    private final OrderAttributionReplayReader attributionReader;
    private final Commands commands;
    private final AdmissionService admission;
    private final QuotePort quotes;
    private final MemberTradingPort members;
    private final MemberTradeFactPort tradeFacts;
    private final JobSchedulerPort jobs;
    private final InventoryTradingPort inventory;
    private final MerchantService merchants;
    private final AddressProtector addresses;
    private final AuditWriter audit;
    private final Outbox outbox;
    private final Clock clock;
    private final long ttl;
    /** 注入领域端口而不是他域Mapper，所有外部渠道网络调用留在后续支付模块。 */
    public OrderService(OrderMapper mapper,Commands commands,QuotePort quotes,MemberTradingPort members,
        com.lrj.trade.order.mapper.OrderAttributionMapper attributions,OrderAttributionReplayReader attributionReader,
        org.springframework.beans.factory.ObjectProvider<OrderAttributionVerifierPort> attributionVerifier,
        InventoryTradingPort inventory,MerchantService merchants,AddressProtector addresses,AuditWriter audit,
        Outbox outbox,Clock clock,AdmissionService admission,MemberTradeFactPort tradeFacts,JobSchedulerPort jobs,@Value("${trade.order.ttl-seconds:900}") long ttl) {
        if(ttl<1 || ttl>1800) throw new IllegalArgumentException("订单有效期配置无效");
        this.mapper=mapper; this.attributions=attributions; this.attributionReader=attributionReader; this.attributionVerifier=attributionVerifier; this.commands=commands; this.quotes=quotes; this.members=members; this.inventory=inventory;
        this.merchants=merchants; this.addresses=addresses; this.audit=audit; this.outbox=outbox; this.clock=clock; this.ttl=ttl;
        this.tradeFacts=tradeFacts;
        this.jobs=jobs; this.admission=admission;
    }
    /** 仅接受报价引用；请求摘要显式使用地址字段，不能使用已脱敏的toString产生碰撞。 */
    public OrderView create(Actor actor,String key,long quoteId,Long warehouse,ShippingAddress address) {
        return create(actor,key,quoteId,warehouse,address,null);
    }
    /** 有令牌的新请求先在事务外验签；原成功在密钥退休或令牌过期后仍按完整请求回放。 */
    public OrderView create(Actor actor,String key,long quoteId,Long warehouse,ShippingAddress address,String referralToken) {
        Checks.text(key,128);
        Checks.positive(quoteId);
        if(warehouse!=null) Checks.positive(warehouse);
        String legacyHash=Checks.hash(quoteId,warehouse,address==null?null:address.recipient(),
            address==null?null:address.phone(),address==null?null:address.address());
        if(referralToken!=null && (referralToken.isBlank() || referralToken.length()>16384))
            throw new TradeException("ORDER_ATTRIBUTION_UNTRUSTED","订单归因令牌无效");
        String hash=referralToken==null?legacyHash:Checks.hash(legacyHash,Checks.hash(referralToken));
        // 先按命令作用域查永久记录，包括去掉token的变更请求，防Commands淘汰后重新解释原键。
        String previous=attributionReplay(attributions.replay(actor.tenantId(),actor.key(),"order.create",key),hash);
        if(previous!=null) return read(actor,previous);
        if(referralToken!=null) {
            previous=commands.completed(actor,"order.create",key,hash);
            if(previous!=null) return read(actor,previous);
            if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                throw new TradeException("ORDER_ATTRIBUTION_TRANSACTION","归因验签不能在业务事务内执行");
        }
        boolean outerTransaction=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        VerifiedOrderAttribution attribution=null;
        if(referralToken!=null) {
            var verifier=attributionVerifier.getIfAvailable();
            if(verifier==null) throw new TradeException("ORDER_ATTRIBUTION_DISABLED","订单归因尚未接通");
            attribution=verifier.verify(actor,members.memberId(actor),quoteId,referralToken);
        }
        final VerifiedOrderAttribution verified=attribution;
        String number=commands.run(actor,"order.create",key,hash,()->{
            // 命令锁后再读永久结果；外层旧RR快照用独立短读，新事务首次一致性读不会锁空范围。
            String replay=attributionReplay(outerTransaction
                ?attributionReader.latest(actor.tenantId(),actor.key(),"order.create",key)
                :attributions.replay(actor.tenantId(),actor.key(),"order.create",key),hash);
            if(replay!=null) return replay;
            admission.acquire(actor.tenantId()).requireOrder();
            return place(actor, key, quotes.requireUsable(actor,quoteId), warehouse, address, verified, hash, null);
        });
        return read(actor,number);
    }

    /** 调用方持有试点引用锁，创建原生报价、订单与库存预占后同时提交关系。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderView createCheckout(Actor actor, CheckoutPrice price, Long warehouse, ShippingAddress address, String merchantNo) {
        admission.acquire(actor.tenantId()).requireOrder();
        var quote = quotes.createCheckout(actor, price);
        return read(actor, place(actor, null, quote, warehouse, address, null, null, merchantNo));
    }

    private String place(Actor actor, String key, Quote quote, Long warehouse, ShippingAddress address,
            VerifiedOrderAttribution verified, String hash, String checkoutMerchant) {
        long quoteId=Long.parseLong(quote.quoteId());
        long member=members.memberId(actor);
        var merchant=merchants.requireActive(actor.tenantId());
        if (checkoutMerchant != null && !checkoutMerchant.equals(merchant.merchantNo()))
            throw new TradeException("CHECKOUT_PILOT_SOURCE_CONFLICT", "收款商户与试点配对不符");
        boolean physical=quote.items().stream().anyMatch(item->item.stockType().equals("PHYSICAL"));
        if(physical && (warehouse==null || address==null)) throw new TradeException("INVALID_ARGUMENT","实物订单必须指定仓库和收货地址");
        if(!physical && (warehouse!=null || address!=null)) throw new TradeException("INVALID_ARGUMENT","纯服务订单不能附带仓库或收货个人信息");
        String no="ORD-"+UUID.randomUUID();
        EncryptedAddress encrypted=physical?addresses.encrypt(actor.tenantId(),no,address):null;
        var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        // 商户锁等待或加密耗时之后再验证，避免用刚刚过期的报价创建订单。
        if(!now.isBefore(quote.expiresAt())) throw new TradeException("QUOTE_EXPIRED","报价已过期");
        boolean free=quote.payableAmount()==0;
        long gross=quote.items().stream().mapToLong(item -> Math.multiplyExact(item.unitPrice(), item.quantity())).reduce(0, Math::addExact);
        if(verified!=null) verified.requireUsable(actor,member,quoteId,now);
        mapper.insertOrder(actor.tenantId(),no,member,quoteId,Long.parseLong(merchant.merchantId()),merchant.merchantNo(),
            warehouse,free?"PAID":"PENDING_PAYMENT",free?"NO_PAYMENT_REQUIRED":"UNPAID",
            gross,gross-quote.payableAmount(),quote.payableAmount(),quote.currency(),now,now.plusSeconds(ttl));
        long order=Long.parseLong(own(actor,member,no,false).orderId());
        if(verified!=null) {
            try { attributions.insert(actor.tenantId(),order,no,actor.key(),"order.create",key,hash,verified,now); }
            catch(org.springframework.dao.DuplicateKeyException conflict) {
                throw new TradeException("ORDER_ATTRIBUTION_CONSUMED","订单归因令牌已消费");
            }
        }
        for(var item:quote.items()) {
            String reservation=item.stockType().equals("PHYSICAL")?no+":"+item.skuId():null;
            long lineGross=Math.multiplyExact(item.unitPrice(),item.quantity());
            mapper.insertItem(actor.tenantId(),order,Long.parseLong(item.skuId()),item.skuRevision(),item.title(),
                JSON.writeValueAsString(item.specification()),item.stockType(),item.quantity(),item.unitPrice(),
                lineGross,lineGross-item.payableAmount(),item.payableAmount(),reservation,now);
            if(reservation!=null) {
                inventory.reserve(actor,reservation,warehouse,Long.parseLong(item.skuId()),item.quantity());
                // 零元订单没有渠道收款，也不设置paidAt或消费首单；仅确认履约库存。
                if(free) inventory.confirm(actor,"zero:"+reservation,reservation);
            }
        }
        if(encrypted!=null) mapper.insertAddress(actor.tenantId(),order,encrypted.recipient(),encrypted.phone(),
            encrypted.address(),encrypted.keyVersion(),now);
        audit.append(actor,"order.create","ORDER",no,"创建报价订单",0,0);
        outbox.append(actor.tenantId(),"order.created.internal.v0",no);
        if(!free) jobs.schedule(actor.tenantId(),JobType.ORDER_RESOLVE,no,now.plusSeconds(ttl));
        return no;
    }
    private String attributionReplay(OrderAttributionReplay row,String hash) {
        if(row==null) return null;
        if(!hash.equals(row.requestHash())) throw new TradeException("IDEMPOTENCY_CONFLICT","相同幂等键的请求内容不同");
        return row.orderNo();
    }
    /** 冻结会员仍可查询历史订单，地址明文不通过普通查询返回。 */
    public OrderView read(Actor actor,String number) { return view(actor.tenantId(),own(actor,members.memberId(actor),number,false)); }
    /** 本人订单列表只查主表，避免有界列表内部产生逐订单明细查询。 */
    public List<OrderHeader> list(Actor actor,long after,int limit) {
        if(after<0 || limit<1 || limit>100) throw new TradeException("INVALID_ARGUMENT","分页参数无效");
        return mapper.listOwn(actor.tenantId(),members.memberId(actor),after,limit);
    }
    /** 无支付尝试许可才可直接释放，有未决可能时只能持久化CLOSING。 */
    public OrderView cancel(Actor actor,String key,String number,String reason) {
        Checks.text(number,64); Checks.text(reason,255);
        String result=commands.run(actor,"order.cancel",key,Checks.hash(number,reason),()->{
            var order=own(actor,members.memberId(actor),number,true);
            if(order.status().equals("CANCELLED") || order.status().equals("CLOSING")) return number;
            if(!order.status().equals("PENDING_PAYMENT")) throw new TradeException("STATE_CONFLICT","当前订单不能直接取消");
            var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
            boolean safe=order.paymentStartedAt()==null;
            String target=safe?"CANCELLED":"CLOSING";
            if(mapper.cancel(actor.tenantId(),Long.parseLong(order.orderId()),order.version(),target,reason,safe?now:null,now)!=1)
                throw new TradeException("VERSION_CONFLICT","订单状态已改变");
            if(safe) for(var item:mapper.items(actor.tenantId(),Long.parseLong(order.orderId())))
                if(item.reservationKey()!=null) inventory.release(actor,"cancel:"+item.reservationKey(),item.reservationKey());
            audit.append(actor,"order."+target,"ORDER",number,reason,order.version(),order.version()+1);
            outbox.append(actor.tenantId(),"order."+target.toLowerCase(Locale.ROOT)+".internal.v0",number);
            if(safe) outbox.orderCancelled(OrderCancelledEvent.create(actor.tenantId(),new OrderCancelledEvent.Data(
                order.orderId(),order.memberId(),now,order.version()+1,"CANCEL_REQUEST")));
            jobs.wake(actor.tenantId(),JobType.ORDER_RESOLVE,number);
            return number;
        });
        return read(actor,result);
    }
    /** 订单行锁裁决许可与取消；支付模块须把意图落库和许可置位放在同一事务。 */
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public OrderHeader authorizePayment(Actor actor,String number) {
        long member=members.requireActive(actor);
        var order=own(actor,member,number,true);
        if(!order.status().equals("PENDING_PAYMENT") || order.payableAmount()==0)
            throw new TradeException("STATE_CONFLICT","订单不允许发起支付");
        var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if(!now.isBefore(order.expiresAt())) throw new TradeException("ORDER_EXPIRED","订单已过支付期限");
        // 支付写路径的门禁不依赖试点开关，关闭接入后存量订单仍须等待资金预留。
        if (mapper.pendingCheckoutOrder(actor.tenantId(), Long.parseLong(order.orderId())) != null)
            throw new TradeException(CHECKOUT_FUNDING_PENDING, "试点订单等待营销预算预留");
        if(order.paymentStartedAt()==null && mapper.markPaymentStarted(actor.tenantId(),Long.parseLong(order.orderId()),order.version(),now)!=1)
            throw new TradeException("VERSION_CONFLICT","订单已改变");
        return own(actor,member,number,true);
    }
    /** 从可信支付边界按租户锁定，不通过用户的会员状态判断已发生资金。 */
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public OrderHeader lockPaymentOrder(long tenant,long order) {
        Checks.positive(tenant); Checks.positive(order);
        return mapper.lockPaymentOrder(tenant,order).stream().findFirst()
            .orElseThrow(()->new TradeException("NOT_FOUND","订单不存在"));
    }
    /** 订单级任务使用稳定订单号定位，不通过会员状态决定已存在订单的资金恢复。 */
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public OrderHeader lockPaymentOrderByNumber(long tenant,String number) {
        Checks.positive(tenant); Checks.text(number,64);
        return mapper.lockByNumber(tenant,number).stream().findFirst()
            .orElseThrow(()->new TradeException("NOT_FOUND","订单不存在"));
    }

    /** 收款主事务内完成三项业务副作用，任一库存或首单失败使整笔收款处理回滚。 */
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public PaymentConfirmation confirmPayment(long tenant,long id,long amount,String currency) {
        var order=lockPaymentOrder(tenant,id);
        if(amount<=0 || amount!=order.payableAmount() || !order.currency().equals(currency)
            || !Set.of("PENDING_PAYMENT","CLOSING").contains(order.status()) || !order.financialStatus().equals("UNPAID"))
            throw new TradeException("STATE_CONFLICT","订单不允许正常收款确认");
        var actor=new Actor(tenant,"urn:trade:payment","settlement");
        for(var item:mapper.items(tenant,id))
            if(item.reservationKey()!=null) inventory.confirm(actor,"paid:"+item.reservationKey(),item.reservationKey());
        var fact=tradeFacts.confirmPaidOrder(tenant,Long.parseLong(order.memberId()),id,amount,currency);
        if(mapper.markPaid(tenant,id,order.version(),fact.confirmedAt())!=1)
            throw new TradeException("VERSION_CONFLICT","订单状态已改变");
        audit.append(actor,"order.paid","ORDER",order.orderNo(),"可信正常足额收款",order.version(),order.version()+1);
        outbox.append(tenant,"order.paid.internal.v0",order.orderNo());
        jobs.wake(tenant,JobType.ORDER_RESOLVE,order.orderNo());
        return new PaymentConfirmation(order.orderId(),fact.memberId(),fact.confirmedAt(),fact.firstOrderPolicy(),
            fact.firstConfirmedPaidOrder(),order.version()+1);
    }
    /** 过期或后台关闭检查不直接放库存，保留与渠道在途请求竞争的屏障。 */
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void beginPaymentClose(long tenant,long id) {
        var order=lockPaymentOrder(tenant,id);
        if(!order.status().equals("PENDING_PAYMENT")) return;
        var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if(mapper.cancel(tenant,id,order.version(),"CLOSING","支付关闭检查",null,now)!=1)
            throw new TradeException("VERSION_CONFLICT","订单状态已改变");
        outbox.append(tenant,"order.closing.internal.v0",order.orderNo());
    }
    /** 仅支付编排证明没有可支付尝试后调用；重复关闭不重复释放。 */
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void completePaymentClose(long tenant,long id) {
        var order=lockPaymentOrder(tenant,id);
        if(order.status().equals("CANCELLED")) return;
        if(!order.status().equals("CLOSING") || !order.financialStatus().equals("UNPAID"))
            throw new TradeException("STATE_CONFLICT","订单不允许关单释放");
        var actor=new Actor(tenant,"urn:trade:payment","close");
        for(var item:mapper.items(tenant,id))
            if(item.reservationKey()!=null) inventory.release(actor,"close:"+item.reservationKey(),item.reservationKey());
        var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if(mapper.finishClose(tenant,id,order.version(),now)!=1)
            throw new TradeException("VERSION_CONFLICT","订单状态已改变");
        audit.append(actor,"order.cancelled","ORDER",order.orderNo(),"支付渠道可靠关闭",order.version(),order.version()+1);
        outbox.append(tenant,"order.cancelled.internal.v0",order.orderNo());
        outbox.orderCancelled(OrderCancelledEvent.create(tenant,new OrderCancelledEvent.Data(
            order.orderId(),order.memberId(),now,order.version()+1,"PAYMENT_NONPAYABLE")));
    }
    private OrderHeader own(Actor actor,long member,String number,boolean lock) {
        Checks.text(number,64);
        return (lock?mapper.lockOwn(actor.tenantId(),member,number):mapper.findOwn(actor.tenantId(),member,number))
            .stream().findFirst().orElseThrow(()->new TradeException("NOT_FOUND","订单不存在"));
    }
    private OrderView view(long tenant,OrderHeader row) {
        List<OrderLine> lines=mapper.items(tenant,Long.parseLong(row.orderId())).stream().map(item->new OrderLine(
            item.itemId(),item.skuId(),item.skuRevision(),item.title(),
            Map.copyOf(JSON.readValue(item.specification(),new TypeReference<Map<String,String>>() {})),
            item.stockType(),item.quantity(),item.unitPrice(),item.payableAmount())).toList();
        return new OrderView(row.orderId(),row.orderNo(),row.quoteId(),row.merchantNo(),row.warehouseId(),row.status(),
            row.financialStatus(),row.payableAmount(),row.currency(),row.expiresAt(),row.paidAt(),row.completedAt(),row.createdAt(),row.version(),lines);
    }
}
