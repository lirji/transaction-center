package com.lrj.trade.inventory.mapper;
import org.apache.ibatis.annotations.*;
import com.lrj.trade.inventory.model.ReturnOperation;
/** 原预占回库预算和永久流水操作持久化边界。 */
@Mapper
public interface InventoryReturnMapper {
    /** 原预占已锁定后占用操作键，避免缺失键范围锁升级。 */
    int insertOperation(@Param("tenant") long tenant,@Param("operation") String operation,@Param("reservation") long reservation,@Param("warehouse") long warehouse,@Param("quantity") long quantity,@Param("hash") String hash,@Param("token") String token);
    /** 当前读用于识别并发或命令淘汰后的同操作重放。 */
    ReturnOperation lockOperation(@Param("tenant") long tenant,@Param("operation") String operation);
    /** 条件扣减原预占预算，不得挪用其他订单的已售余额。 */
    int consumeBudget(@Param("tenant") long tenant,@Param("reservation") long reservation,@Param("quantity") long quantity);
    /** 双仓均已按序锁定，条件更新保留各仓及全局守恒。 */
    int moveBalance(@Param("tenant") long tenant,@Param("warehouse") long warehouse,@Param("sku") long sku,@Param("available") long available,@Param("sold") long sold,@Param("total") long total,@Param("minimumSold") long minimumSold);
}
