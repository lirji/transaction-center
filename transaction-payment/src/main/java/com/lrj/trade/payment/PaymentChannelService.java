package com.lrj.trade.payment;

import com.lrj.trade.core.Checks;
import com.lrj.trade.core.TradeException;
import com.lrj.trade.payment.model.PaymentDispatch;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 渠道策略注册及耐久证据交接；无默认模拟渠道，缺适配器时安全等待。 */
@Service
public class PaymentChannelService implements PaymentChannelPort {
    private final Map<String,PaymentChannelAdapter> adapters;
    private final PaymentCallbackService inbox;

    /** 只接受依赖注入的固定实现，重复代码立即失败，避免首个或末个实现随机生效。 */
    public PaymentChannelService(List<PaymentChannelAdapter> configured,PaymentCallbackService inbox) {
        Map<String,PaymentChannelAdapter> entries=new HashMap<>();
        for(var adapter:configured) {
            String code=Checks.text(adapter.channelCode(),64);
            if(entries.putIfAbsent(code,adapter)!=null)
                throw new IllegalArgumentException("渠道实现重复");
        }
        this.adapters=Map.copyOf(entries); this.inbox=inbox;
    }

    /** 调用与耐久接收分段；收到证据即使原租约过期也须保存，不能丢弃真实资金。 */
    @Override
    @Transactional(propagation=Propagation.NEVER)
    public Outcome invoke(long tenant,PaymentDispatch plan) {
        Checks.positive(tenant);
        if(plan==null || !Set.of("CREATE","QUERY","CLOSE").contains(plan.action()))
            throw new TradeException("INVALID_ARGUMENT","不是可执行的渠道调用计划");
        // 显式保护也覆盖未经过Spring代理的内部误用，不能持业务事务发起渠道请求。
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("渠道调用不能位于数据库事务内");
        var adapter=adapters.get(plan.channelCode());
        if(adapter==null) return Outcome.UNAVAILABLE;
        PaymentChannelAdapter.Reply reply;
        try { reply=adapter.execute(plan,Duration.ofSeconds(10)); }
        catch(ChannelCallUncertainException uncertain) { return Outcome.UNKNOWN; }
        if(reply==null) throw new TradeException("CHANNEL_PROTOCOL_INVALID","渠道返回缺少结果");
        if(reply.state()==PaymentChannelAdapter.State.UNKNOWN) return Outcome.UNKNOWN;
        if(reply.state()==PaymentChannelAdapter.State.PENDING) return Outcome.PENDING;
        if(reply.state()==PaymentChannelAdapter.State.REPLAYABLE_ABSENCE) {
            // 普通不存在不够：仅认证过幂等补建能力的QUERY结果可申请重新检查本地许可。
            if(!"QUERY".equals(plan.action()) || !adapter.supportsSafeCreateReplay())
                throw new TradeException("CHANNEL_PROTOCOL_INVALID","渠道不具备安全补建合同");
            return Outcome.REPLAYABLE_ABSENCE;
        }
        var evidence=reply.evidence();
        if(!Objects.equals(plan.requestNo(),evidence.requestNo())
            || !Objects.equals(plan.channelCode(),evidence.channelCode())
            || !Objects.equals(plan.channelMerchantNo(),evidence.channelMerchantNo()))
            throw new TradeException("CHANNEL_BINDING_CONFLICT","渠道证据与原调用归属不一致");
        // 不按预期金额过滤真实异常资金；金额裁决由持久接收后的业务处理承担。
        inbox.accept(tenant,evidence);
        return Outcome.EVIDENCE_ACCEPTED;
    }
}
