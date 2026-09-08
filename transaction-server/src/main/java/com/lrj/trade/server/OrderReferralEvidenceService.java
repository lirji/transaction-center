package com.lrj.trade.server;
import com.lrj.trade.core.*;
import com.lrj.trade.order.OrderEvidencePort;
import com.lrj.trade.payment.PaymentEvidencePort;
import com.lrj.trade.member.MemberPaidEvidencePort;
import com.lrj.trade.member.CustomerFactPort;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
/** 可信内部订单证据查询；统一主库快照，由独立服务身份边界控制HTTP访问。 */
@Service
public class OrderReferralEvidenceService {
    private static final String POLICY="FIRST_CONFIRMED_PAID_ORDER_V1";
    private final OrderEvidencePort orders;
    private final PaymentEvidencePort payments;
    private final MemberPaidEvidencePort members;
    private final TransactionTemplate snapshot;
    private final Clock clock;
    private final CustomerFactPort customers;
    /** 每次查询建立独立只读RR事务，避免被调用者已有RC事务悄悄降低隔离级别。 */
    public OrderReferralEvidenceService(OrderEvidencePort orders,PaymentEvidencePort payments,MemberPaidEvidencePort members,PlatformTransactionManager manager,Clock clock,CustomerFactPort customers) {
        this.orders=orders; this.payments=payments; this.members=members; this.clock=clock; this.customers=customers;
        snapshot=new TransactionTemplate(manager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setReadOnly(true); snapshot.setTimeout(3);
    }
    /**
     * 每个请求项独立返回FOUND/NOT_FOUND；重复ID保留输入顺序但数据库只读去重集合。
     * 此独立快照有意不见外层未提交写入，不能用来组装当前写事务的V1事件载荷。
     */
    public List<Result> query(long tenant,List<Long> requested) {
        Checks.positive(tenant);
        if(requested==null || requested.isEmpty() || requested.size()>100 || requested.stream().anyMatch(id->id==null || id<=0))
            throw new TradeException("INVALID_ARGUMENT","证据订单列表必须包含1到100个有效编号");
        var input=List.copyOf(requested);
        return snapshot.execute(tx->readSnapshot(tenant,input));
    }
    private List<Result> readSnapshot(long tenant,List<Long> input) {
            var ids=input.stream().distinct().toList();
            var headers=orders.read(tenant,ids).stream().collect(Collectors.toMap(r->r.orderId(),Function.identity()));
            var funds=payments.read(tenant,ids).stream().collect(Collectors.toMap(r->r.orderId(),Function.identity()));
            var facts=members.read(tenant,ids).stream().collect(Collectors.toMap(r->r.orderId(),Function.identity()));
            // 这是本次读取完成时间，不是假装可以重建过去某一时刻的金额快照。
            Instant asOf=clock.instant().truncatedTo(ChronoUnit.MILLIS);
            return input.stream().map(id->{
                String key=Long.toString(id); var order=headers.get(key);
                if(order==null) return new Result(key,"NOT_FOUND",null);
                var money=funds.get(key); var fact=facts.get(key);
                long paid=0,refunded=0,pending=0; boolean first=false;
                if(money!=null) {
                    if(order.paidAt()==null || fact==null || !order.memberId().equals(fact.memberId())
                        || !POLICY.equals(fact.policy()) || !order.currency().equals(money.currency())
                        || !order.currency().equals(fact.currency()) || money.paidAmount()!=order.payableAmount()
                        || money.paidAmount()!=fact.paidAmount() || money.paidAmount()<=0) throw inconsistent();
                    paid=money.paidAmount(); refunded=money.refundedAmount(); pending=money.pendingRefundAmount(); first=fact.first();
                    if(refunded<0 || pending<0 || refunded>paid || pending>paid-refunded) throw inconsistent();
                } else if(order.paidAt()!=null || fact!=null) throw inconsistent();
                return new Result(key,"FOUND",new Evidence(key,order.memberId(),order.evidenceVersion(),POLICY,first,
                    paid,refunded,pending,paid-refunded,paid-refunded-pending,order.currency(),order.paidAt(),order.completedAt(),asOf));
            }).toList();
    }
    /**
     * 全局永久首单按本地确认时间筛选[from,to)，金额仍取当前快照，不能改选期间第二单。
     * READY只表示证据存在，NOT_FOUND可以在未来真实首付后改变；都不是奖励资格预占。
     */
    public FirstResult first(long tenant,long member,Instant from,Instant to,String policy) {
        Checks.positive(tenant); Checks.positive(member);
        if(from==null || to==null || !POLICY.equals(policy)) throw invalidWindow();
        Instant start=from.truncatedTo(ChronoUnit.MILLIS),end=to.truncatedTo(ChronoUnit.MILLIS);
        if(!start.isBefore(end)) throw invalidWindow();
        return snapshot.execute(tx->{
            var rows=customers.read(tenant,member);
            if(rows.isEmpty()) return new FirstResult("NOT_FOUND",null,null);
            var fact=rows.getFirst();
            if(!fact.everPaid()) {
                if(fact.firstPaidOrderId()!=null || fact.firstConfirmedAt()!=null) throw inconsistent();
                return new FirstResult("NOT_FOUND",null,null);
            }
            if(fact.firstPaidOrderId()==null || fact.firstConfirmedAt()==null
                || fact.firstConfirmedAt().isBefore(fact.registeredAt()) || fact.firstConfirmedAt().isAfter(clock.instant())) throw inconsistent();
            // 私有组装复用本次已建立的主库快照，不能调用会另开事务的公开query。
            var result=readSnapshot(tenant,List.of(Long.parseLong(fact.firstPaidOrderId()))).getFirst();
            if(result.evidence()==null || !result.evidence().memberId().equals(Long.toString(member))
                || !result.evidence().isFirstConfirmedPaidOrder()) throw inconsistent();
            if(fact.firstConfirmedAt().isBefore(start) || !fact.firstConfirmedAt().isBefore(end)) return new FirstResult("NOT_FOUND",null,null);
            return new FirstResult("READY",fact.firstConfirmedAt(),result.evidence());
        });
    }
    private TradeException invalidWindow() { return new TradeException("INVALID_ARGUMENT","首单策略或毫秒期间无效"); }
    /** 选单依据为本地首确认时间；金额与证据版本仍属于当前同一快照。 */
    public record FirstResult(String state,Instant firstConfirmedAt,Evidence evidence) {}
    private TradeException inconsistent() { return new TradeException("EVIDENCE_UNAVAILABLE","订单、正常收款与首单事实暂不一致，不能确认奖励资格"); }
    /** 每个请求项独立状态；其他租户与不存在同样返回NOT_FOUND。 */
    public record Result(String orderId,String status,Evidence evidence) {}
    /** 正式交易字段合同；金额均为币种最小单位，首单与完成不能从净额猜测。 */
    public record Evidence(String orderId,String memberId,long evidenceVersion,String firstOrderPolicy,boolean isFirstConfirmedPaidOrder,
        long paidAmount,long refundedAmount,long pendingRefundAmount,long netPaidAmount,long conservativeEligibleAmount,
        String currency,Instant paidAt,Instant completedAt,Instant asOf) {}
}
