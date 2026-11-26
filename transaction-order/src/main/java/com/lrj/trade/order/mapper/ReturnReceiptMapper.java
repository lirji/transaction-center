package com.lrj.trade.order.mapper;
import org.apache.ibatis.annotations.*;
import com.lrj.trade.order.model.ReturnReceipt;
/** 退货验收与原订单项累计实收预算。 */
@Mapper
public interface ReturnReceiptMapper {
    /** 来源事件永久唯一；先插入再当前读，避免缺失键范围锁。 */
    /** 原验收号与可读号随验收和库存关联同事务提交。 */
    int insert(@Param("tenant") long tenant,@Param("number") String number,@Param("businessNo") String businessNo,@Param("order") long order,@Param("item") long item,@Param("actor") String actor,@Param("event") String event,@Param("warehouse") long warehouse,@Param("received") long received,@Param("saleable") long saleable,@Param("evidence") String evidence,@Param("hash") String hash,@Param("createdAt") java.time.Instant createdAt);
    /** 当前读核对原验收，不依赖短期命令缓存。 */
    ReturnReceipt lockEvent(@Param("tenant") long tenant,@Param("actor") String actor,@Param("event") String event);
    /** 订单锁串行化后仍使用数量条件守卫，实收不能超过冻结全量交付数量。 */
    int consumeBudget(@Param("tenant") long tenant,@Param("order") long order,@Param("item") long item,@Param("received") long received);
}
