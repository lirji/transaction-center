package com.lrj.trade.core;
import java.time.Instant;
import java.util.List;
/** 各资金域只暴露固定聚合，不将租户或资金明细传给监控线程。 */
public interface FinancialSnapshotPort {
    /** 固定域名用于区分样本新鲜度，禁止来自用户输入。 */
    String domain();
    /** 固定指标键，不接受运行期高基数标签。 */
    List<String> keys();
    /** 各域独立短只读事务，失败由采样器保留旧样本。 */
    Snapshot snapshot();
    /** 数量最多10001，达到上限仅代表下界；年龄起点不随采样或重试重置。 */
    record Row(String metric,long countLowerBound,Instant oldestAt,Instant observedAt) {}
    /** 防御性复制保证Gauge读取期间样本不可变。 */
    record Snapshot(List<Row> rows) {
        /** 调用者不得改变监控线程已发布的样本行。 */
        public Snapshot { rows=List.copyOf(rows); }
    }
}
