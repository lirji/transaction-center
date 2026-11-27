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

    /** 按观察时订单状态聚合，并用相同窗口核对已确认的应付订单。 */
    List<OperationsReport.OrderState> orderStates(@Param("tenant") long tenant, @Param("currency") String currency,
                                                   @Param("q") AdminQuery query);

    /** 按北京时间分桶，以原已确认资金事实和创建订单为来源。 */
    List<OperationsReport.DailyTotals> daily(@Param("tenant") long tenant, @Param("currency") String currency,
                                              @Param("q") AdminQuery query);

    /** 财务明细计数和行查询共用资金事实定义及过滤条件。 */
    long factCount(@Param("tenant") long tenant, @Param("currency") String currency, @Param("q") AdminQuery query);

    /** 按账本主键排序后直接选取所请求的页。 */
    List<OperationsReport.Fact> factsAtOffset(@Param("tenant") long tenant, @Param("currency") String currency,
            @Param("q") AdminQuery query, @Param("offset") long offset);

    /** 只从真实订单绑定求总量，预算引用来自已验真的回执。 */
    OperationsReport.LinkedTotals linkedTotals(@Param("tenant") long tenant, @Param("currency") String currency,
                                               @Param("q") AdminQuery query);

    /** 来源标识来自冻结快照的允许字段，不输出完整证据或签名。 */
    List<OperationsReport.LinkedCheckout> linkedRows(@Param("tenant") long tenant, @Param("currency") String currency,
            @Param("q") AdminQuery query, @Param("offset") long offset);

    /** 复用同一笔资金事实定义，按账本游标读取所有符合条件的交易。 */
    List<OperationsReport.Fact> facts(@Param("tenant") long tenant, @Param("currency") String currency,
                                      @Param("q") AdminQuery query);
}
