package com.lrj.trade.payment;

import com.lrj.trade.core.Checks;
import com.lrj.trade.core.TradeException;
import com.lrj.trade.payment.mapper.ChannelMapper;
import com.lrj.trade.payment.mapper.PaymentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 原路解析只访问支付本域；历史尝试始终引用原channel_id，冻结不阻断合法资金查询。 */
@Service
public class PaymentChannelBindingService implements PaymentChannelBindingPort {
    private final PaymentMapper payments;
    private final ChannelMapper channels;
    /** 依赖强类型持久化边界，不接收动态SQL或用户提供的凭据。 */
    public PaymentChannelBindingService(PaymentMapper payments,ChannelMapper channels) {
        this.payments=payments; this.channels=channels;
    }
    /** 短只读事务返回必要绑定后结束；调用方不得把远端网络调用放入外层事务。 */
    @Override
    @Transactional(readOnly=true,timeout=10)
    public Binding resolve(long tenant,long attemptId) {
        Checks.positive(tenant); Checks.positive(attemptId);
        var attempts=payments.findAttemptById(tenant,attemptId);
        if(attempts.size()!=1) throw new TradeException("NOT_FOUND","原支付尝试不存在");
        var attempt=attempts.getFirst();
        var configs=channels.findById(tenant,Long.parseLong(attempt.channelId()));
        if(configs.size()!=1) throw new TradeException("PAYMENT_CHANNEL_BINDING_UNAVAILABLE","原渠道配置不可用");
        var config=configs.getFirst();
        // 不按ACTIVE或当前商户重选，避免退款迁移到后建收款主体；密钥内容仍由托管服务解析。
        if(config.credentialRef()==null || config.credentialRef().isBlank())
            throw new TradeException("PAYMENT_CHANNEL_BINDING_UNAVAILABLE","原渠道凭据引用不可用");
        return new Binding(tenant,attemptId,config.channelId(),config.channelCode(),config.channelMerchantNo(),
            config.currency(),config.credentialRef(),config.version());
    }
}
