package com.lrj.trade.order.mapper;
import com.lrj.trade.order.model.*;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 来源回执先于订单和库存锁，申请与实际验收分别持久化。 */
@Mapper
public interface FulfillmentIngressMapper {
    /** 首次来源事件占唯一键，不覆盖原请求和时间。 */
    void insert(@Param("tenant") long tenant,@Param("actor") String actor,@Param("operation") String operation,@Param("source") String source,@Param("hash") String hash,@Param("at") Instant at);
    /** 当前读永久结果而不依赖HTTP缓存保留期。 */
    FulfillmentIngress lock(@Param("tenant") long tenant,@Param("actor") String actor,@Param("operation") String operation,@Param("source") String source);
    /** 领域事实和首次回执同事务提交。 */
    int finish(@Param("tenant") long tenant,@Param("id") long id,@Param("number") String number,@Param("status") String status,@Param("version") long version);
    /** 原明细及交付事实只读核验，不提前锁已有RMA。 */
    List<ReturnTarget> target(@Param("tenant") long tenant,@Param("item") long item);
    /** 租户订单ID解析为已有核心使用的订单号，不接受跨租户引用。 */
    List<String> orderNo(@Param("tenant") long tenant,@Param("id") long id);
    /** 创建退货意图，不增加已收到量和可售库存。 */
    /** 通过全局索引确认候选的退货业务号冲突。 */
    String lockReturnBusinessNumber(@Param("number") String number);

    void createReturn(@Param("tenant") long tenant,@Param("number") String number,@Param("order") long order,@Param("item") long item,@Param("warehouse") long warehouse,@Param("quantity") long quantity,@Param("createdAt") java.time.Instant createdAt);
    /** 管理查询不读取证据原文。 */
    List<ReturnRequest> findReturn(@Param("tenant") long tenant,@Param("number") String number);
    /** 先锁申请，再由原验收服务锁订单和库存。 */
    List<ReturnRequest> lockReturn(@Param("tenant") long tenant,@Param("number") String number);
    /** 原子记录验收终态与对应永久实收编号。 */
    int finishReturn(@Param("tenant") long tenant,@Param("number") String number,@Param("version") long version,@Param("receipt") String receipt);
    /** 只包含申请校验所需的不可变订单明细。 */
    record ReturnTarget(long orderId,String orderNo,String stockType,long quantity) {}
}
