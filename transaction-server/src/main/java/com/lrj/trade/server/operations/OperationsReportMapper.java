package com.lrj.trade.server.operations;

import com.lrj.trade.core.query.AdminQuery;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 跨域经营查询仅在同一个本地数据库读取并聚合订单及确认资金。 */
@Mapper
public interface OperationsReportMapper {
    /** 单条语句读取一致视图，避免一个请求内将不同快照的结果相减。 */
    OperationsReport.Totals summary(@Param("tenant") long tenant, @Param("currency") String currency,
                                    @Param("q") AdminQuery query);

    /** 复用同一笔资金事实定义，按账本游标读取所有符合条件的交易。 */
    List<OperationsReport.Fact> facts(@Param("tenant") long tenant, @Param("currency") String currency,
                                      @Param("q") AdminQuery query);
}
