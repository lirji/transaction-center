package com.lrj.trade.jobs.mapper;
import com.lrj.trade.jobs.model.*;
import com.lrj.trade.core.query.AdminQuery;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 专用只读Mapper，不提供写入、锁定或跨租户扫描。 */
@Mapper
public interface AdminJobMapper {
    /** 租户限定的根记录分页，多读一条用于判断末页。 */
    List<AdminJobView> page(@Param("tenant") long tenant,@Param("q") AdminQuery q,@Param("at") Instant at);
    /** 查询总量使用与游标列表相同的租户及筛选条件。 */
    long pageCount(@Param("tenant") long tenant,@Param("q") AdminQuery q,@Param("at") Instant at);
    /** 在服务端核对过总量后对同一查询范围取指定页。 */
    List<AdminJobView> pageAtOffset(@Param("tenant") long tenant,@Param("q") AdminQuery q,@Param("at") Instant at, @Param("offset") long offset);
    /** 精确查询仍必须限定租户，外租户与不存在相同。 */
    List<AdminJobView> find(@Param("tenant") long tenant,@Param("number") String number,@Param("at") Instant at);
    /** 所有租约标志共用本次主库时间，避免应用机器时钟偏差。 */
    Instant now();
    /** 只取本租户原退款恢复任务的结构化错误，不使用回调正文。 */
    String refundReason(@Param("tenant") long tenant,@Param("number") String number);
    /** 固定类型适配器只解析确实存在的本租户业务资源，坏键返回空。 */
    List<AdminBusinessReference> reference(@Param("tenant") long tenant,@Param("number") String number);
}
