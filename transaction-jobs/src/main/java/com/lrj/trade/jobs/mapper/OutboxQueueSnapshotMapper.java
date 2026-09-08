package com.lrj.trade.jobs.mapper;
import com.lrj.trade.jobs.model.OutboxQueueSnapshotRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
/** 仅固定发布类型的全局监控聚合例外，不允许参数化业务筛选或外部调用。 */
@Mapper
public interface OutboxQueueSnapshotMapper {
    /** 每状态最多10001个索引项，防止精确COUNT在巨大积压上无限扫描。 */
    List<OutboxQueueSnapshotRow> snapshot();
}
