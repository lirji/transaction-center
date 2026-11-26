package com.lrj.trade.order.mapper;

import com.lrj.trade.order.model.*;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 订单持久化接口，业务状态判断及库存调用留在事务服务。 */
@Mapper
public interface OrderMapper {
    /** 可信支付模块通过订单Port锁定资源，不要求会员仍为有效状态。 */
    List<OrderHeader> lockPaymentOrder(@Param("tenant") long tenant,@Param("order") long order);
    /** 订单级关闭任务按唯一订单号加锁，与创建支付共用订单行锁。 */
    List<OrderHeader> lockByNumber(@Param("tenant") long tenant,@Param("number") String number);
    /** 滚动升级按稳定主键有界扫描所有订单，避免金额过滤使大量零元订单被无界跳过。 */
    List<OrderHeader> resolutionCandidates(@Param("tenant") long tenant,@Param("after") long after,@Param("limit") int limit);
    /** 正常足额收款只允许从未付或关闭中转换，商业完成时间仍为空。 */
    int markPaid(@Param("tenant") long tenant,@Param("order") long order,@Param("version") long version,@Param("now") Instant now);
    /** 可靠关单结束才转换已取消；保留支付许可和审计历史。 */
    int finishClose(@Param("tenant") long tenant,@Param("order") long order,@Param("version") long version,@Param("now") Instant now);
    /** 全局编号索引的当前读只用于判定候选号碰撞。 */
    String lockBusinessNumber(@Param("number") String number);
    /** 同报价唯一键阻止换幂等键重复下单，商户和金额来自服务端。 */
    int insertOrder(@Param("tenant") long tenant,@Param("number") String number,@Param("member") long member,
        @Param("quote") long quote,@Param("merchantId") long merchantId,@Param("merchantNo") String merchantNo,
        @Param("warehouse") Long warehouse,@Param("status") String status,@Param("financial") String financial,
        @Param("gross") long gross,@Param("discount") long discount,@Param("amount") long amount,
        @Param("currency") String currency,@Param("created") Instant created,@Param("expires") Instant expires);
    /** 读取当前会员订单，不能以知道订单编号绕过归属。 */
    List<OrderHeader> findOwn(@Param("tenant") long tenant,@Param("member") long member,@Param("number") String number,
        @Param("businessNumber") boolean businessNumber);
    /** 在支付许可或取消前锁定同一订单行，阻止跨终态并发更新。 */
    List<OrderHeader> lockOwn(@Param("tenant") long tenant,@Param("member") long member,@Param("number") String number);
    /** 有界主键游标查询本人订单主表，不产生支付或库存副作用。 */
    List<OrderHeader> listOwn(@Param("tenant") long tenant,@Param("member") long member,@Param("after") long after,@Param("limit") int limit);
    /** 写入报价快照，不重新计算价格或从请求体复制金额。 */
    int insertItem(@Param("tenant") long tenant,@Param("order") long order,@Param("sku") long sku,
        @Param("revision") long revision,@Param("title") String title,@Param("specification") String specification,
        @Param("type") String type,@Param("quantity") long quantity,@Param("price") long price,
        @Param("gross") long gross,@Param("discount") long discount,@Param("amount") long amount,
        @Param("reservation") String reservation,@Param("created") Instant created);
    /** 读取明细按SKU排序，与创建预占及取消释放使用相同锁序。 */
    List<OrderItemRow> items(@Param("tenant") long tenant,@Param("order") long order);
    /** 仅写入密文，字段用途与订单已通过加密关联数据绑定。 */
    int insertAddress(@Param("tenant") long tenant,@Param("order") long order,@Param("recipient") byte[] recipient,
        @Param("phone") byte[] phone,@Param("address") byte[] address,@Param("keyVersion") String keyVersion,@Param("created") Instant created);
    /** 首次许可支付写入屏障；后续渠道模块不得在未获得许可时调用渠道。 */
    int markPaymentStarted(@Param("tenant") long tenant,@Param("order") long order,@Param("version") long version,@Param("now") Instant now);
    /** 原生试点订单须等待独立确认的资金预留，常规订单没有此门禁。 */
    Long pendingCheckoutOrder(@Param("tenant") long tenant, @Param("order") long order);
    /** 无支付许可时直接取消，有支付许可时只能进入CLOSING等待可靠终态。 */
    int cancel(@Param("tenant") long tenant,@Param("order") long order,@Param("version") long version,
        @Param("status") String status,@Param("reason") String reason,@Param("cancelled") Instant cancelled,@Param("now") Instant now);
}
