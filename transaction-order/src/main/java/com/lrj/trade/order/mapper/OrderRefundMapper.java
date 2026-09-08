package com.lrj.trade.order.mapper;
import com.lrj.trade.order.OrderRefundPort;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 订单退款适配SQL独立隔离，不把资金账本所有权迁入订单。 */
@Mapper
public interface OrderRefundMapper {
    /** 读取冻结运费和税额，不能用请求金额作为上限。 */
    List<OrderRefundPort.Charges> charges(@Param("tenant") long tenant,@Param("order") long order);
    /** 只推进财务状态/证据版本，履约字段和库存均不变。 */
    int updateFinancial(@Param("tenant") long tenant,@Param("order") long order,@Param("version") long version,@Param("financial") String financial,@Param("now") Instant now);
}
