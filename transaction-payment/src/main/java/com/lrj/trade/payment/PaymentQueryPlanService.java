package com.lrj.trade.payment;
import com.lrj.trade.core.*;
import com.lrj.trade.payment.mapper.PaymentQueryPlanMapper;
import com.lrj.trade.payment.model.PaymentDispatch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/** 固定原channelId的只读计划，不因终态或冻结配置换用新商户。 */
@Service
public class PaymentQueryPlanService implements PaymentQueryPlanPort {
    private final PaymentQueryPlanMapper mapper;
    /** 域内Mapper负责绑定查询，跨域调用仅看到强类型Port。 */
    public PaymentQueryPlanService(PaymentQueryPlanMapper mapper) {this.mapper=mapper;}
    /** 即使原尝试尚未提交，也只查询原号，不创建请求或推进派发屏障。 */
    @Override @Transactional(readOnly=true,timeout=5)
    public PaymentDispatch query(long tenant,long channel,String referenceType,String reference) {
        Checks.positive(tenant);Checks.positive(channel);Checks.text(reference,64);
        var rows=switch(referenceType==null?"":referenceType) {
            case "PAYMENT_ATTEMPT" -> mapper.attempt(tenant,channel,reference);
            case "RECEIPT" -> mapper.receipt(tenant,channel,reference);
            default -> throw new TradeException("INVALID_ARGUMENT","支付查单只接受原请求或实收引用");
        };
        if(rows.size()!=1) throw new TradeException("RECONCILIATION_BINDING_CONFLICT","支付查单原渠道归属不匹配");
        var plan=rows.getFirst();if(!"QUERY".equals(plan.action()) || plan.credentialRef()==null || plan.credentialRef().isBlank()) throw new TradeException("RECONCILIATION_QUERY_UNAVAILABLE","原渠道查询配置不可用");
        return plan;
    }
    /** 是否派发由不可逆submitted_at决定，不能仅根据当前状态推测。 */
    @Override @Transactional(readOnly=true,timeout=5)
    public RefundSource refundSource(long tenant,long channel,String refundNo) {
        Checks.positive(tenant);Checks.positive(channel);Checks.text(refundNo,64);
        var rows=mapper.refund(tenant,channel,refundNo);
        if(rows.size()!=1) throw new TradeException("RECONCILIATION_BINDING_CONFLICT","退款查单原渠道归属不匹配");
        return rows.getFirst();
    }
}
