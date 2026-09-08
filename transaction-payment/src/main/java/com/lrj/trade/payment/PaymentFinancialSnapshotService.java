package com.lrj.trade.payment;
import com.lrj.trade.core.FinancialSnapshotPort;
import com.lrj.trade.payment.mapper.PaymentFinancialSnapshotMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 各域独立事务与时间，防止某域采样失败被其他域健康样本掩盖。 */
@Service
public class PaymentFinancialSnapshotService implements FinancialSnapshotPort {
    private final PaymentFinancialSnapshotMapper mapper;
    /** 持久化边界只依赖本域Mapper。 */
    public PaymentFinancialSnapshotService(PaymentFinancialSnapshotMapper mapper) { this.mapper=mapper; }
    /** 固定域标签，不返回业务路由信息。 */
    @Override public String domain() { return "payment"; }
    /** 固定状态集合同时用于验证结果完整性。 */
    @Override public List<String> keys() { return List.of("payment_unknown","refund_held","refund_submitted","callback_pending","callback_review"); }
    /** 独立主库RC快照，三秒预算且无锁，不拖入调用方业务事务。 */
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true,timeout=3)
    public Snapshot snapshot() {
        var rows=mapper.snapshot();
        if(!rows.stream().map(Row::metric).toList().equals(keys())) throw new IllegalStateException("资金观测状态不完整");
        return new Snapshot(rows);
    }
}
