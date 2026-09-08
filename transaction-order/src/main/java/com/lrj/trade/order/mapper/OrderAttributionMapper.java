package com.lrj.trade.order.mapper;

import com.lrj.trade.order.model.OrderAttributionReplay;
import com.lrj.trade.order.model.VerifiedOrderAttribution;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 永久快照只允许插入和精确读取，取消退款不能释放或覆盖令牌身份。 */
@Mapper
public interface OrderAttributionMapper {
    /** 精确读取原主体、操作和幂等键的成功引用，调用方再比较完整摘要。 */
    OrderAttributionReplay replay(@Param("tenant") long tenant,@Param("actorKey") String actorKey,
        @Param("operation") String operation,@Param("requestKey") String requestKey);
    /** 单次消费唯一键冲突使订单、库存和Outbox整事务回滚。 */
    int insert(@Param("tenant") long tenant,@Param("order") long order,@Param("orderNo") String orderNo,
        @Param("actorKey") String actorKey,@Param("operation") String operation,@Param("requestKey") String requestKey,
        @Param("requestHash") String requestHash,@Param("claim") VerifiedOrderAttribution claim,@Param("now") Instant now);
}
