package com.lrj.trade.inventory.mapper;

import com.lrj.trade.core.query.AdminQuery;
import com.lrj.trade.inventory.InventoryAlertService.Alert;
import com.lrj.trade.inventory.InventoryAlertService.Totals;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 补货运营投影只查询和维护当前租户已有余额的预警字段。 */
@Mapper
public interface InventoryAlertMapper {
    /** 同一仓库/SKU 范围内按真实可用量和阈值求状态统计。 */
    List<Totals> totals(@Param("tenant") long tenant, @Param("q") AdminQuery query);

    /** 预警范围保留每行余额及独立配置版本。 */
    List<Alert> page(@Param("tenant") long tenant, @Param("q") AdminQuery query, @Param("offset") long offset);

    /** 通过期望阈值版本更新，库存行的数量与交易版本不变。 */
    int set(@Param("tenant") long tenant, @Param("warehouse") long warehouse, @Param("sku") long sku,
            @Param("threshold") Long threshold, @Param("revision") long revision);

    /** 外租户与不存在仅按本租户进行判断。 */
    boolean exists(@Param("tenant") long tenant, @Param("warehouse") long warehouse, @Param("sku") long sku);
}
