package com.lrj.trade.payment;

import com.lrj.trade.payment.model.*;
import java.time.Instant;

/** 支付域拥有退款资金与账本；退款应用必须通过此端口加入同一本地事务，不跨域读Mapper。 */
public interface PaymentRefundPort {
    /** 已锁订单后定位唯一正常收款；用户退款申请不需要也不能自行指定异常收款号。 */
    com.lrj.trade.payment.model.RefundSource lockNormalSource(long tenant,long order);
    /** 按真实收款锁订单/意图/收款，返回冻结来源和最新额度；不执行用户授权检查。 */
    RefundSource lockSource(long tenant,String receiptNo);
    /** 永久退款号去重，绑定订单/来源/金额/币种；同号释放后重放不能重新预占。 */
    RefundHold reserve(long tenant,String refundNo,String receiptNo,long orderId,long amount,String currency);
    /** 渠道调用前持久化不可逆派发屏障，UNKNOWN仍占额度；不是单独可调用的网络许可。 */
    RefundHold markSubmitted(long tenant,String refundNo);
    /** 仅从未派发才允许本地拒绝/撤销释放；不能把未知当失败。 */
    RefundHold releaseUnsubmitted(long tenant,String refundNo,String evidenceRef);
    /** 仅内部已验证渠道不可再成功的终态调用，普通超时/查不到不满足合同。 */
    RefundHold releaseVerifiedFailure(long tenant,String refundNo,String evidenceRef);
    /** 可信成功同时转移额度和追加唯一资金账本；调用方还需同事务更新退款应用及fencing。 */
    RefundHold confirmSucceeded(long tenant,String refundNo,String providerRefundNo,long amount,String currency,
        Instant providerSucceededAt,String evidenceRef);
}
