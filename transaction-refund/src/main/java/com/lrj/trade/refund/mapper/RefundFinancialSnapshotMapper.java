package com.lrj.trade.refund.mapper;
import com.lrj.trade.core.FinancialSnapshotPort;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
/** 内部固定状态全局聚合窄例外，只返回有界数量和时间。 */
@Mapper
public interface RefundFinancialSnapshotMapper {
    /** 每状态最多10001项覆盖索引，禁止调用者指定筛选或明细。 */
    List<FinancialSnapshotPort.Row> snapshot();
}
