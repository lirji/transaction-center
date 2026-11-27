package com.lrj.trade.order.mapper;
import com.lrj.trade.order.model.*;
import com.lrj.trade.core.query.AdminQuery;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 专用只读Mapper，不提供写入、锁定或跨租户扫描。 */
@Mapper
public interface AdminReturnMapper {
    /** 租户限定的根记录分页，多读一条用于判断末页。 */
    List<AdminReturnView> page(@Param("tenant") long tenant,@Param("q") AdminQuery q);
    /** 查询总量使用与游标列表相同的租户及筛选条件。 */
    long pageCount(@Param("tenant") long tenant,@Param("q") AdminQuery q);
    /** 在服务端核对过总量后对同一查询范围取指定页。 */
    List<AdminReturnView> pageAtOffset(@Param("tenant") long tenant,@Param("q") AdminQuery q, @Param("offset") long offset);
    /** 精确查询仍必须限定租户，外租户与不存在相同。 */
    List<AdminReturnView> find(@Param("tenant") long tenant,@Param("number") String number);
    /** 同租户已验证父资源的只读明细。 */
    List<AdminInspectionView> inspection(@Param("tenant") long tenant,@Param("number") String number);
}
