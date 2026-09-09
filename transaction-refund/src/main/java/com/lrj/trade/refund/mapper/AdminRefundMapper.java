package com.lrj.trade.refund.mapper;
import com.lrj.trade.refund.model.*;
import com.lrj.trade.core.query.AdminQuery;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 专用只读Mapper，不提供写入、锁定或跨租户扫描。 */
@Mapper
public interface AdminRefundMapper {
    /** 租户限定的根记录分页，多读一条用于判断末页。 */
    List<AdminRefundView> page(@Param("tenant") long tenant,@Param("q") AdminQuery q);
    /** 精确查询仍必须限定租户，外租户与不存在相同。 */
    List<AdminRefundView> find(@Param("tenant") long tenant,@Param("number") String number);
    /** 同租户已验证父资源的只读明细。 */
    List<AdminRefundItem> items(@Param("tenant") long tenant,@Param("number") String number);
}
