package com.lrj.trade.core.mapper;
import com.lrj.trade.core.*;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 操作到差异再到任务的单向锁序，不通过外键在占幂等键时提前锁差异。 */
@Mapper
public interface ReconciliationOperationMapper {
    /** 占永久主体请求键，重复不得更新原内容。 */
    void insert(@Param("tenant") long tenant,@Param("number") String number,@Param("businessNo") String businessNo,@Param("now") java.time.Instant now,@Param("actor") String actor,@Param("key") String key,@Param("hash") String hash,@Param("caseNo") String caseNo,@Param("action") String action,@Param("reason") String reason,@Param("ticket") String ticket);
    /** 当前读判断冲突发生在可读号还是已受理请求。 */
    String lockBusinessNumber(@Param("number") String number);
    /** 当前读永久请求结果。 */
    List<ReconciliationOperation> lockRequest(@Param("tenant") long tenant,@Param("actor") String actor,@Param("key") String key);
    /** 处理器首先锁自身操作。 */
    List<ReconciliationOperation> lock(@Param("tenant") long tenant,@Param("number") String number);
    /** 接着锁差异，不锁旧操作或资金数据。 */
    List<ReconciliationCaseGuard> lockCase(@Param("tenant") long tenant,@Param("caseNo") String caseNo);
    /** 旧操作终态普通读，用于允许新一轮独立查询，不形成反向锁。 */
    List<ReconciliationOperation> find(@Param("tenant") long tenant,@Param("number") String number);
    /** 受理新操作推进差异版本及操作关联，仍不结案。 */
    int beginCase(@Param("tenant") long tenant,@Param("caseNo") String caseNo,@Param("version") long version,@Param("number") String number);
    /** 冻结首次受理结果和查询归属。 */
    int accept(@Param("tenant") long tenant,@Param("id") long id,@Param("channel") long channel,@Param("type") String type,@Param("reference") String reference,@Param("caseVersion") long caseVersion);
    /** 仅更新查询操作，任务fencing失败由调用事务全部回滚。 */
    int mark(@Param("tenant") long tenant,@Param("id") long id,@Param("status") String status,@Param("code") String code,@Param("terminal") boolean terminal);
    /** 管理视图基于持久执行状态，不返回幂等键与凭据。 */
    List<ReconciliationOperation> page(@Param("tenant") long tenant,@Param("state") String state,@Param("after") long after,@Param("limit") int limit,@Param("number") String number);
}
