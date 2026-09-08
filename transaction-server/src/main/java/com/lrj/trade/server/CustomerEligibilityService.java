package com.lrj.trade.server;
import com.lrj.trade.core.*;
import com.lrj.trade.member.CustomerFactPort;
import com.lrj.trade.order.OrderEvidencePort;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
/** 交易原生新客策略只读评估，不预占资格，也不代替营销身份映射与风控。 */
@Service
public class CustomerEligibilityService {
    private static final String REGISTERED="REGISTERED_DURING_CAMPAIGN_V1",NEVER="NEVER_CONFIRMED_PAID_V1";
    private final CustomerFactPort members;
    private final OrderEvidencePort orders;
    private final Clock clock;
    private final TransactionTemplate snapshot;
    /** 独立主库RR快照保留会员事实与首单编号一致；不用于写事务Outbox构造。 */
    public CustomerEligibilityService(CustomerFactPort members,OrderEvidencePort orders,Clock clock,PlatformTransactionManager manager) {
        this.members=members; this.orders=orders; this.clock=clock;
        snapshot=new TransactionTemplate(manager); snapshot.setReadOnly(true); snapshot.setTimeout(3);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    /**
     * 仅两种不可变事实策略可按指定非未来时点评估；不支持通用历史金额或身份合并回溯。
     * everPaid始终是当前不可逆历史，everPaidAtEvaluation明确区分该时点是否已首付。
     */
    public Result query(long tenant,long member,String policy,Instant campaignStartAt,Instant evaluatedAt) {
        Checks.positive(tenant); Checks.positive(member);
        if(!REGISTERED.equals(policy) && !NEVER.equals(policy)) throw invalid();
        Instant now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant requestedAt=evaluatedAt==null?null:evaluatedAt.truncatedTo(ChronoUnit.MILLIS);
        if((requestedAt!=null && requestedAt.isAfter(now)) || (REGISTERED.equals(policy) && campaignStartAt==null)) throw invalid();
        Instant start=campaignStartAt==null?null:campaignStartAt.truncatedTo(ChronoUnit.MILLIS);
        return snapshot.execute(tx->{
            var facts=members.read(tenant,member);
            if(facts.isEmpty()) return new Result("NOT_FOUND",null);
            var fact=facts.getFirst(); String firstOrder=null;
            if(fact.everPaid()) {
                if(fact.firstPaidOrderId()==null || fact.firstConfirmedAt()==null || fact.firstConfirmedAt().isBefore(fact.registeredAt())) throw unavailable();
                var first=orders.read(tenant,List.of(Long.parseLong(fact.firstPaidOrderId())));
                if(first.size()!=1 || !first.getFirst().memberId().equals(fact.memberId())) throw unavailable();
                firstOrder=first.getFirst().orderNo();
            } else if(fact.firstPaidOrderId()!=null || fact.firstConfirmedAt()!=null) throw unavailable();
            // 默认当前查询按已建立快照裁决，不能用建立快照前的时钟把已观察的首付排除。
            Instant at=requestedAt==null?clock.instant().truncatedTo(ChronoUnit.MILLIS):requestedAt;
            if(requestedAt==null && (fact.registeredAt().isAfter(at) || (fact.firstConfirmedAt()!=null && fact.firstConfirmedAt().isAfter(at)))) throw unavailable();
            boolean registered=!fact.registeredAt().isAfter(at);
            boolean paidAtEvaluation=requestedAt==null?fact.everPaid():fact.firstConfirmedAt()!=null && !fact.firstConfirmedAt().isAfter(at);
            boolean eligible=registered && (REGISTERED.equals(policy)?!fact.registeredAt().isBefore(start):!paidAtEvaluation);
            String reason=!registered?"NOT_REGISTERED_AT_EVALUATION":eligible?"ELIGIBLE":REGISTERED.equals(policy)?"REGISTERED_BEFORE_CAMPAIGN":"ALREADY_CONFIRMED_PAID";
            return new Result("READY",new Evidence(fact.memberId(),fact.registeredAt(),fact.everPaid(),firstOrder,fact.factVersion(),eligible,
                List.of(reason),at,policy,paidAtEvaluation,clock.instant().truncatedTo(ChronoUnit.MILLIS)));
        });
    }
    private TradeException invalid() { return new TradeException("INVALID_ARGUMENT","资格策略、活动开始时间或评估时点无效"); }
    private TradeException unavailable() { return new TradeException("EVIDENCE_UNAVAILABLE","会员首单证据不完整，不能确认新客资格"); }
    /** 不存在不默认新客；营销权威主体映射尚未就绪时另由适配层返回PENDING。 */
    public record Result(String status,Evidence evidence) {}
    /** 当前不可逆事实与指定时点策略结果并列，事实版本只表示本次读取依据。 */
    public record Evidence(String memberId,Instant registeredAt,boolean everPaid,String firstPaidOrderNo,long factVersion,
        boolean eligible,List<String> reasonCodes,Instant evaluatedAt,String policyVersion,boolean everPaidAtEvaluation,Instant observedAt) {
        /** 防止调用方修改评估原因。 */
        public Evidence { reasonCodes=List.copyOf(reasonCodes); }
    }
}
