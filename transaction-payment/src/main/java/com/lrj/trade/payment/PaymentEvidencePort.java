package com.lrj.trade.payment;
import java.util.List;
import com.lrj.trade.payment.model.PaymentEvidenceRow;
/** 交易主库普通一致读，必须加入同一证据查询事务；不是资格预占。 */
public interface PaymentEvidencePort {
    /** 最多100个订单ID，不存在或其他租户不返回行；不启用行锁或副本查询。 */
    List<PaymentEvidenceRow> read(long tenant,List<Long> orders);
}
