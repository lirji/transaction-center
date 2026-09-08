package com.lrj.trade.payment;

import com.lrj.trade.core.*;
import com.lrj.trade.order.OrderPaymentPort;
import com.lrj.trade.payment.mapper.*;
import com.lrj.trade.payment.model.RiskEvaluation;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 风险评估历史与一次性结果；先订单锁再支付锁，未知永不产生支付许可。 */
@Service
public class PaymentRiskService implements PaymentRiskPort {
    private final PaymentRiskMapper risks;
    private final PaymentMapper payments;
    private final ChannelMapper channels;
    private final OrderPaymentPort orders;
    private final Clock clock;
    private final PaymentRiskProvider provider;

    /** 最多配置一个经认证的策略，缺配置保持等待，不注入默认放行实现。 */
    public PaymentRiskService(PaymentRiskMapper risks,PaymentMapper payments,ChannelMapper channels,
        OrderPaymentPort orders,Clock clock,List<PaymentRiskProvider> providers) {
        if(providers.size()>1) throw new IllegalArgumentException("支付风险策略只能显式配置一个");
        this.risks=risks; this.payments=payments; this.channels=channels; this.orders=orders; this.clock=clock;
        this.provider=providers.isEmpty()?null:providers.getFirst();
    }

    /** 输入冻结并追加评估轮次；拒绝永久保留，允许过期或待定超时才建立新评估号。 */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public RiskEvaluation prepareInTransaction(long tenant,String requestNo) {
        Checks.positive(tenant); Checks.text(requestNo,64);
        var reference=payments.findAttempt(tenant,requestNo).stream().findFirst()
            .orElseThrow(()->new TradeException("NOT_FOUND","支付尝试不存在"));
        orders.lockPaymentOrder(tenant,Long.parseLong(reference.orderId()));
        var intent=payments.lockByOrder(tenant,Long.parseLong(reference.orderId())).getFirst();
        var attempt=payments.lockAttempt(tenant,requestNo).getFirst();
        var channel=channels.findById(tenant,Long.parseLong(attempt.channelId())).getFirst();
        var now=now();
        var existing=risks.latest(tenant,requestNo);
        String hash=Checks.hash("PAYMENT_RISK_V1",tenant,attempt.attemptId(),requestNo,intent.orderId(),
            intent.memberId(),channel.channelCode(),channel.channelMerchantNo(),intent.expectedAmount(),intent.currency());
        if(!existing.isEmpty()) {
            var current=existing.getFirst();
            if(!hash.equals(current.inputHash()) || !hash.equals(inputHash(current)))
                throw new TradeException("RISK_BINDING_CONFLICT","冻结风险输入不一致");
            if(current.state().equals("DENY") || (current.state().equals("ALLOW") && now.isBefore(current.validUntil()))
                || (current.state().equals("PENDING") && now.isBefore(current.deadline()))) return current;
        }
        String evaluationNo="RISK-"+UUID.randomUUID();
        risks.insert(evaluationNo,tenant,Long.parseLong(attempt.attemptId()),requestNo,
            Long.parseLong(intent.orderId()),Long.parseLong(intent.memberId()),channel.channelCode(),
            channel.channelMerchantNo(),intent.expectedAmount(),intent.currency(),hash,now,now.plusSeconds(300));
        if(risks.point(tenant,requestNo,evaluationNo)!=1) throw new TradeException("STATE_CONFLICT","风险评估指针写入失败");
        return risks.latest(tenant,requestNo).getFirst();
    }

    /** 风控网络禁止事务；暂缺、异常与空响应均退为WAIT，真实超时由认证适配器落实。 */
    @Override @Transactional(propagation=Propagation.NEVER)
    public Decision evaluate(RiskEvaluation evaluation) {
        Objects.requireNonNull(evaluation);
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new TradeException("TRANSACTION_BOUNDARY","风控调用不能持数据库事务");
        if(provider==null) return waitFor(evaluation);
        try {
            var result=provider.evaluate(evaluation,Duration.ofSeconds(3));
            return result==null?waitFor(evaluation):result;
        } catch(RuntimeException unavailable) {
            // 不记录供应商异常对象，避免报文、身份凭据或URL泄露；业务不得fail-open。
            return waitFor(evaluation);
        }
    }

    /** 只接受当前轮次且完整绑定的可信结果；调用方fencing失败时此写入一起回滚。 */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void acceptInTransaction(RiskEvaluation expected,Decision result) {
        Objects.requireNonNull(expected);
        var reference=payments.findAttempt(expected.tenantId(),expected.requestNo()).stream().findFirst()
            .orElseThrow(()->new TradeException("NOT_FOUND","支付尝试不存在"));
        orders.lockPaymentOrder(expected.tenantId(),Long.parseLong(reference.orderId()));
        var current=risks.latest(expected.tenantId(),expected.requestNo()).stream().findFirst()
            .orElseThrow(()->new TradeException("RISK_BINDING_CONFLICT","风险评估不存在"));
        if(!current.evaluationNo().equals(expected.evaluationNo()) || !current.inputHash().equals(inputHash(expected))
            || !current.requestedAt().equals(expected.requestedAt()) || !current.deadline().equals(expected.deadline())
            || result==null || result.action()==null || result.tenantId()!=current.tenantId()
            || !current.evaluationNo().equals(result.evaluationNo()) || !current.requestNo().equals(result.requestNo())
            || !current.inputHash().equals(result.inputHash()))
            throw new TradeException("RISK_BINDING_CONFLICT","风险结果归属或输入不一致");
        if(result.action()==Action.WAIT) return;
        Checks.text(result.decisionId(),128); Checks.text(result.ruleVersion(),128); Checks.text(result.modelVersion(),128);
        if(!current.state().equals("PENDING")) {
            if(current.state().equals(result.action().name()) && Objects.equals(current.decisionId(),result.decisionId())
                && Objects.equals(current.ruleVersion(),result.ruleVersion()) && Objects.equals(current.modelVersion(),result.modelVersion())
                && Objects.equals(current.validUntil(),result.validUntil())) return;
            throw new TradeException("RISK_DECISION_CONFLICT","风险结果已固定，不能覆盖");
        }
        var now=now();
        if(!now.isBefore(current.deadline())) throw new TradeException("RISK_EXPIRED","风险评估已过接收期限");
        if(result.action()==Action.ALLOW) {
            if(result.validUntil()==null || !now.isBefore(result.validUntil()) || result.validUntil().isAfter(now.plusSeconds(60))
                || result.validUntil().isAfter(current.deadline()) || result.validUntil().getNano()%1_000_000!=0)
                throw new TradeException("RISK_PROTOCOL_INVALID","风险允许期限无效");
        } else if(result.validUntil()!=null) throw new TradeException("RISK_PROTOCOL_INVALID","拒绝结果不能携带允许期限");
        if(risks.decide(current.tenantId(),current.evaluationNo(),result.action().name(),result.decisionId(),
            result.ruleVersion(),result.modelVersion(),result.validUntil())!=1)
            throw new TradeException("RISK_DECISION_CONFLICT","风险结果已改变");
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private String inputHash(RiskEvaluation e) {
        return Checks.hash("PAYMENT_RISK_V1",e.tenantId(),e.attemptId(),e.requestNo(),e.orderId(),e.memberId(),
            e.channelCode(),e.channelMerchantNo(),e.amount(),e.currency());
    }
    private Decision waitFor(RiskEvaluation e) {
        return new Decision(e.evaluationNo(),e.tenantId(),e.requestNo(),e.inputHash(),Action.WAIT,null,null,null,null);
    }
}
