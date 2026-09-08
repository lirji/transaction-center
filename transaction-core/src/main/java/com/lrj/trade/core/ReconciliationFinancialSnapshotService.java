package com.lrj.trade.core;
import com.lrj.trade.core.FinancialSnapshotPort;
import com.lrj.trade.core.mapper.ReconciliationFinancialSnapshotMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 各域独立事务与时间，防止某域采样失败被其他域健康样本掩盖。 */
@Service
public class ReconciliationFinancialSnapshotService implements FinancialSnapshotPort {
    private final ReconciliationFinancialSnapshotMapper mapper;
    /** 持久化边界只依赖本域Mapper。 */
    public ReconciliationFinancialSnapshotService(ReconciliationFinancialSnapshotMapper mapper) { this.mapper=mapper; }
    /** 固定域标签，不返回业务路由信息。 */
    @Override public String domain() { return "reconciliation"; }
    /** 固定状态集合同时用于验证结果完整性。 */
    @Override public List<String> keys() { return List.of("difference_open","difference_processing"); }
    /** 独立主库RC快照，三秒预算且无锁，不拖入调用方业务事务。 */
    @Override @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true,timeout=3)
    public Snapshot snapshot() {
        var rows=mapper.snapshot();
        if(!rows.stream().map(Row::metric).toList().equals(keys())) throw new IllegalStateException("资金观测状态不完整");
        return new Snapshot(rows);
    }
}
