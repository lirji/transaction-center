package com.lrj.trade.payment.mapper;
import com.lrj.trade.payment.model.AdminPaymentView;
import com.lrj.trade.core.query.AdminQuery;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 专用只读Mapper，不提供写入、锁定或跨租户扫描。 */
@Mapper
public interface AdminPaymentMapper {
    /** 租户限定的根记录分页，多读一条用于判断末页。 */
    List<AdminPaymentView> page(@Param("tenant") long tenant,@Param("q") AdminQuery q);
    /** 查询总量使用与游标列表相同的租户及筛选条件。 */
    long pageCount(@Param("tenant") long tenant,@Param("q") AdminQuery q);
    /** 在服务端核对过总量后对同一查询范围取指定页。 */
    List<AdminPaymentView> pageAtOffset(@Param("tenant") long tenant,@Param("q") AdminQuery q, @Param("offset") long offset);
    /** 精确查询仍必须限定租户，外租户与不存在相同。 */
    List<AdminPaymentView> find(@Param("tenant") long tenant,@Param("number") String number);
}
