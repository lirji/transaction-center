package com.lrj.trade.order.mapper;

import com.lrj.trade.order.model.FulfillmentRecord;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 履约持久化只操作订单域表；所有转换在已锁订单的本地事务内。 */
@Mapper
public interface FulfillmentMapper {
    /** 唯一订单/来源事件先占位再当前读，不对不存在事件加范围锁。 */
    int insertDelivery(@Param("tenant") long tenant,@Param("order") long order,@Param("number") String number,@Param("actor") String actor,@Param("event") String event,@Param("evidence") String evidence,@Param("at") Instant at,@Param("hash") String hash,@Param("now") Instant now);
    /** 订单锁后读取永久整单履约，不依据短期命令记录判断已交付。 */
    List<FulfillmentRecord> lockByOrder(@Param("tenant") long tenant,@Param("order") long order);
    /** 首次实际交付只推进履约状态，不修改财务和支付时间。 */
    int markDelivered(@Param("tenant") long tenant,@Param("order") long order,@Param("version") long version,@Param("now") Instant now);
    /** 商业完成与来源事件唯一键同事务，不能覆盖已有完成证据。 */
    int complete(@Param("tenant") long tenant,@Param("order") long order,@Param("version") long version,@Param("actor") String actor,@Param("event") String event,@Param("evidence") String evidence,@Param("at") Instant at,@Param("hash") String hash);
    /** 独立商业完成才写订单completedAt，财务状态保持原值。 */
    int markCompleted(@Param("tenant") long tenant,@Param("order") long order,@Param("version") long version,@Param("at") Instant at,@Param("now") Instant now);
}
