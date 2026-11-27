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
    /** 计数与取页复用同一组查询条件，不包含游标位置。 */
    long count(@Param("tenant") long tenant, @Param("q") AdminQuery query);
    /** 先从索引范围选页内键，再读取本页业务字段。 */
    List<AdminOrderView> numberedPage(@Param("tenant") long tenant, @Param("q") AdminQuery query,
            @Param("offset") long offset);
    /** 精确查询仍必须限定租户，外租户与不存在相同。 */
    List<AdminOrderView> find(@Param("tenant") long tenant,@Param("number") String number);
    /** 详情显示创建时关联的报价业务号，订单分页不读取报价表。 */
    String quoteNumber(@Param("tenant") long tenant,@Param("quoteId") String quoteId);
    /** 同租户已验证父资源的只读明细。 */
    List<AdminOrderItem> items(@Param("tenant") long tenant,@Param("number") String number);
    /** 同租户已验证父资源的只读明细。 */
    List<AdminFulfillmentView> fulfillment(@Param("tenant") long tenant,@Param("number") String number);
}
