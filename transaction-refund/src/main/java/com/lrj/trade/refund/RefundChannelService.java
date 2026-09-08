package com.lrj.trade.refund;

import com.lrj.trade.core.Checks;
import com.lrj.trade.core.TradeException;
import com.lrj.trade.payment.PaymentChannelBindingPort;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 注册原路退款策略并交接持久证据；没有耐久接收能力时禁止先发起远端退款。 */
@Service
public class RefundChannelService implements RefundChannelPort {
    private final Map<String,RefundChannelAdapter> adapters;
    private final RefundEvidencePort inbox;
    private final PaymentChannelBindingPort bindings;

    /** 固定策略及唯一接收器；缺配置保持不可用，重复实现启动即失败。 */
    public RefundChannelService(List<RefundChannelAdapter> configured,List<RefundEvidencePort> receivers,PaymentChannelBindingPort bindings) {
        this.bindings=Objects.requireNonNull(bindings,"原渠道绑定解析器不能为空");
        Map<String,RefundChannelAdapter> entries=new HashMap<>();
        for(var adapter:configured) {
            String code=Checks.text(adapter.channelCode(),64);
            if(entries.putIfAbsent(code,adapter)!=null) throw new IllegalArgumentException("退款渠道实现重复");
        }
        if(receivers.size()>1) throw new IllegalArgumentException("退款证据接收实现重复");
        this.adapters=Map.copyOf(entries);
        this.inbox=receivers.isEmpty()?null:receivers.getFirst();
    }

    /** 旧租约不控制证据接收；证据保存失败必须向上传播，不能伪报已接收。 */
    @Override
    @Transactional(propagation=Propagation.NEVER)
    public Outcome invoke(long tenant,RefundExecutionPort.Plan plan) {
        Checks.positive(tenant);
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("退款渠道调用不能位于数据库事务内");
        validate(tenant,plan);
        var adapter=adapters.get(plan.channelCode());
        if(adapter==null || inbox==null) return Outcome.UNAVAILABLE;
        long attempt;
        try { attempt=Long.parseLong(plan.paymentAttemptId()); }
        catch(NumberFormatException invalid) { throw new TradeException("INVALID_ARGUMENT","原支付尝试编号无效"); }
        Checks.positive(attempt);
        var binding=bindings.resolve(tenant,attempt);
        if(binding==null || binding.tenantId()!=tenant || binding.attemptId()!=attempt
            || !Objects.equals(binding.channelCode(),plan.channelCode())
            || !Objects.equals(binding.channelMerchantNo(),plan.channelMerchantNo())
            || !Objects.equals(binding.currency(),plan.currency())
            || binding.credentialRef()==null || binding.credentialRef().isBlank())
            throw new TradeException("REFUND_CHANNEL_BINDING_CONFLICT","原渠道绑定与退款计划不一致");
        // 解析器的短事务必须已经结束，不能持有数据库连接等待远端渠道。
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("原渠道解析事务未结束");
        RefundChannelAdapter.Reply reply;
        try { reply=adapter.execute(plan,binding,Duration.ofSeconds(10)); }
        catch(RefundChannelAdapter.Uncertain uncertain) { return Outcome.UNKNOWN; }
        if(reply==null) throw new TradeException("REFUND_CHANNEL_PROTOCOL_INVALID","退款渠道缺少结果");
        if(reply.state()==RefundChannelAdapter.State.UNKNOWN) return Outcome.UNKNOWN;
        if(reply.state()==RefundChannelAdapter.State.PENDING) return Outcome.PENDING;
        if(reply.state()==RefundChannelAdapter.State.REPLAYABLE_ABSENCE) {
            if(!plan.action().equals("QUERY") || !adapter.supportsSafeCreateReplay())
                throw new TradeException("REFUND_CHANNEL_PROTOCOL_INVALID","渠道不具备安全同号补发合同");
            return Outcome.REPLAYABLE_ABSENCE;
        }
        var evidence=reply.evidence();
        if(!Objects.equals(plan.channelCode(),evidence.channelCode())
            || !Objects.equals(plan.channelMerchantNo(),evidence.channelMerchantNo())
            || !Objects.equals(plan.merchantRefundNo(),evidence.merchantRefundNo()))
            throw new TradeException("REFUND_CHANNEL_BINDING_CONFLICT","退款证据与调用归属不一致");
        // 同路同号的异常金额、币种或原扣款号也必须留存，交给持久处理器隔离核对，不能丢失真实资金。
        inbox.accept(tenant,evidence);
        return Outcome.EVIDENCE_ACCEPTED;
    }

    private static void validate(long tenant,RefundExecutionPort.Plan plan) {
        if(plan==null || tenant!=plan.tenantId() || plan.action()==null
            || !Set.of("CREATE","QUERY").contains(plan.action()))
            throw new TradeException("INVALID_ARGUMENT","无效退款调用计划");
        Checks.text(plan.refundNo(),64); Checks.text(plan.merchantRefundNo(),64);
        Checks.text(plan.receiptNo(),64); Checks.text(plan.paymentAttemptId(),64);
        Checks.text(plan.channelCode(),64); Checks.text(plan.channelMerchantNo(),255);
        Checks.text(plan.providerTransactionNo(),255);
        if(plan.amount()<=0 || plan.amount()>9007199254740991L || plan.currency()==null
            || !plan.currency().matches("[A-Z]{3}"))
            throw new TradeException("INVALID_ARGUMENT","无效退款金额或币种");
    }
}
