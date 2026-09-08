package com.lrj.trade.payment.mapper;
import com.lrj.trade.payment.PaymentQueryPlanPort;
import com.lrj.trade.payment.model.PaymentDispatch;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 强类型只读计划；只返回原channelId绑定下数据，不接受客户端渠道参数。 */
@Mapper
public interface PaymentQueryPlanMapper {
    /** 原请求编号查询计划。 */
    List<PaymentDispatch> attempt(@Param("tenant") long tenant,@Param("channel") long channel,@Param("reference") String reference);
    /** 收款引用关联原请求查询计划。 */
    List<PaymentDispatch> receipt(@Param("tenant") long tenant,@Param("channel") long channel,@Param("reference") String reference);
    /** 资金退款原收款与派发事实。 */
    List<PaymentQueryPlanPort.RefundSource> refund(@Param("tenant") long tenant,@Param("channel") long channel,@Param("reference") String reference);
}
