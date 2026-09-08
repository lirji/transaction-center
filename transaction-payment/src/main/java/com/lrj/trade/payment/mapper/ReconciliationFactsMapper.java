package com.lrj.trade.payment.mapper;
import com.lrj.trade.core.ReconciliationFactsPort.Fact;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 候选先限量再关联渠道，避免稀疏渠道过滤触发全账本扫描。 */
@Mapper
public interface ReconciliationFactsMapper {
    /** 租户索引尾部读取已提交最大ID，不能当成提交水位。 */
    Long upper(@Param("tenant") long tenant);
    /** 只取有界候选ID，不在这个扫描步骤追加跨表过滤。 */
    List<Long> candidates(@Param("tenant") long tenant,@Param("after") long after,@Param("upper") long upper,@Param("limit") int limit);
    /** 详情仅查询候选集合，并核对原渠道绑定和退款来源收款。 */
    List<Fact> facts(@Param("tenant") long tenant,@Param("channel") long channel,@Param("ids") List<Long> ids);
}
