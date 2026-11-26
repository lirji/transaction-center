package com.lrj.trade.order.mapper;
import com.lrj.trade.order.model.*;
import com.lrj.trade.core.query.AdminQuery;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 专用只读Mapper，不提供写入、锁定或跨租户扫描。 */
@Mapper
public interface AdminOrderMapper {
    /** 租户限定的根记录分页，多读一条用于判断末页。 */
    List<AdminOrderView> page(@Param("tenant") long tenant,@Param("q") AdminQuery q);
    /** 精确查询仍必须限定租户，外租户与不存在相同。 */
    List<AdminOrderView> find(@Param("tenant") long tenant,@Param("number") String number,@Param("businessNumber") boolean businessNumber);
    /** 同租户已验证父资源的只读明细。 */
    List<AdminOrderItem> items(@Param("tenant") long tenant,@Param("number") String number);
    /** 同租户已验证父资源的只读明细。 */
    List<AdminFulfillmentView> fulfillment(@Param("tenant") long tenant,@Param("number") String number);
}
