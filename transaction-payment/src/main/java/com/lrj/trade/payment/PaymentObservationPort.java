package com.lrj.trade.payment;

import com.lrj.trade.payment.model.PaymentReceipt;
import java.time.Instant;

/** 仅供已完成商户绑定和原始报文验签或认证查单的内部适配器调用，禁止公开给用户。 */
public interface PaymentObservationPort {
    /** 持久记录真实交易号；渠道时间只作证据，异常资金不能触发第二次履约。 */
    PaymentReceipt received(long tenant,String requestNo,String providerTransactionNo,long amount,String currency,Instant providerPaidAt,String evidenceRef);
    /**
     * 仅接受渠道对同商户请求号不可再支付的持久终态证明；普通HTTP成功、未知、查询不存在
     * 或本地TTL均不满足此合同。上游须先校验绑定、证明来源和渠道可靠关单语义。
     */
    void closed(long tenant,String requestNo,String evidenceRef);
}
