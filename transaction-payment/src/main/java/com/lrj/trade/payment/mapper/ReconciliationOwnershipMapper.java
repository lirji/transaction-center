package com.lrj.trade.payment.mapper;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 三种固定业务引用只返回渠道ID，不向跨域调用者泄露密钥引用。 */
@Mapper
public interface ReconciliationOwnershipMapper {
    /** 原支付请求号的不可变渠道。 */
    List<Long> attempt(@Param("tenant") long tenant,@Param("reference") String reference);
    /** 实际收款沿原尝试解析渠道。 */
    List<Long> receipt(@Param("tenant") long tenant,@Param("reference") String reference);
    /** 资金退款沿原收款解析渠道，包含在途及已结束历史。 */
    List<Long> refund(@Param("tenant") long tenant,@Param("reference") String reference);
}
