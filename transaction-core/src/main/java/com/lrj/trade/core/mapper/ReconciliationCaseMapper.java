package com.lrj.trade.core.mapper;
import com.lrj.trade.core.*;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 固定receipt到case锁顺序，正文冲突只拒绝，不覆盖已受理证据。 */
@Mapper
public interface ReconciliationCaseMapper {
    /** 先占来源唯一键避免查空间隙锁升级。 */
    void receipt(@Param("tenant") long tenant,@Param("source") String source,@Param("event") String event,@Param("hash") String hash);
    /** 当前读核永久来源键内容。 */
    List<ReconciliationReportReceipt> lockReceipt(@Param("tenant") long tenant,@Param("source") String source,@Param("event") String event);
    /** 占业务唯一键，不更新既有业务内容。 */
    void insertCase(@Param("tenant") long tenant,@Param("number") String number,@Param("businessNo") String businessNo,@Param("now") java.time.Instant now,@Param("source") String source,@Param("report") ReconciliationCaseService.Report report,@Param("hash") String hash);
    /** 当前读业务键，允许不同传输事件归于同一差异。 */
    List<ReconciliationCase> lockBusiness(@Param("tenant") long tenant,@Param("source") String source,@Param("report") ReconciliationCaseService.Report report);
    /** 只读不可变前序关联，不提前锁定其他case。 */
    List<ReconciliationCase> find(@Param("tenant") long tenant,@Param("number") String number);
    /** 首次结果与审计在同一事务保存。 */
    int finish(@Param("tenant") long tenant,@Param("id") long id,@Param("number") String number);
    /** 租户状态队列有界遍历。 */
    List<ReconciliationCase> page(@Param("tenant") long tenant,@Param("state") String state,@Param("after") long after,@Param("limit") int limit,@Param("number") String number);
    /** 与旧列表共享查询范围，计算指定条件下的全量条数。 */
    long pageCount(@Param("tenant") long tenant, @Param("state") String state, @Param("number") String number);
    /** 取准确总量已核对后的页内安全投影。 */
    List<ReconciliationCase> pageAtOffset(@Param("tenant") long tenant, @Param("state") String state, @Param("number") String number, @Param("limit") int limit, @Param("offset") long offset);
}
