package com.lrj.trade.inventory.mapper;

import com.lrj.trade.core.query.AdminQuery;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.lrj.trade.inventory.model.*;

/** InventoryService的持久化边界；SQL仅位于同名XML，事务由业务层统一编排。 */
@Mapper
public interface InventoryMapper {
    /** 新增租户仓库，重复业务编号由唯一约束拒绝。 */
    int insertWarehouse(@Param("tenant") long tenant, @Param("warehouseNo") String warehouseNo, @Param("name") String name);

    /** 读取本事务创建的仓库编号。 */
    String findWarehouseId(@Param("tenant") long tenant, @Param("warehouseNo") String warehouseNo);

    /** 建立库存余额行，已存在时不覆盖任何库存数量。 */
    int insertBalanceIfAbsent(@Param("tenant") long tenant, @Param("warehouseId") long warehouseId, @Param("skuId") long skuId);

    /** 原子条件更新可售量及总量，不能扣减至负库存。 */
    int adjustBalance(@Param("availableDelta") long availableDelta, @Param("totalDelta") long totalDelta, @Param("tenant") long tenant, @Param("warehouseId") long warehouseId, @Param("skuId") long skuId, @Param("minimumAvailable") long minimumAvailable);

    /** 条件扣减可售并增加预占，防止并发超卖。 */
    int reserveBalance(@Param("availableDelta") long availableDelta, @Param("reservedDelta") long reservedDelta, @Param("tenant") long tenant, @Param("warehouseId") long warehouseId, @Param("skuId") long skuId, @Param("minimumAvailable") long minimumAvailable);

    /** 追加预占事实，与余额及流水在同一事务提交。 */
    int insertReservation(@Param("tenant") long tenant, @Param("reservationKey") String reservationKey, @Param("warehouseId") long warehouseId, @Param("skuId") long skuId, @Param("quantity") long quantity);

    /** 按租户稳定键读取预占编号。 */
    String findReservationId(@Param("tenant") long tenant, @Param("reservationKey") String reservationKey);

    /** 锁定预占事实，防止确认与释放同时生效。 */
    List<Reservation> lockReservation(@Param("tenant") long tenant, @Param("reservationKey") String reservationKey);

    /** 原子转移预占数量，必须满足预占余额下限。 */
    int settleReservedBalance(@Param("availableDelta") long availableDelta, @Param("reservedDelta") long reservedDelta, @Param("soldDelta") long soldDelta, @Param("tenant") long tenant, @Param("warehouseId") long warehouseId, @Param("skuId") long skuId, @Param("minimumReserved") long minimumReserved);

    /** 持有预占锁时更新终态，不独立判断支付或关单事实。 */
    int updateReservationStatus(@Param("status") String status, @Param("tenant") long tenant, @Param("reservationId") String reservationId);

    /** 租户内有界分页库存余额。 */
    List<Balance> listBalances(@Param("tenant") long tenant, @Param("after") long after, @Param("limit") int limit);

    /** 租户内有界分页仓库，前端无需硬编码编号。 */
    List<Warehouse> listWarehouses(@Param("tenant") long tenant, @Param("after") long after, @Param("limit") int limit);
    /** 与旧列表共享查询范围，计算指定条件下的全量条数。 */
    long listWarehousesCount(@Param("tenant") long tenant);
    /** 取准确总量已核对后的页内安全投影。 */
    List<Warehouse> listWarehousesAtOffset(@Param("tenant") long tenant, @Param("limit") int limit, @Param("offset") long offset);

    /** 强制仓库及规格过滤，使用索引有界查询流水。 */
    List<LedgerEntry> listLedgerEntries(@Param("tenant") long tenant, @Param("warehouseId") long warehouseId, @Param("skuId") long skuId, @Param("after") long after, @Param("limit") int limit);
    /** 与旧列表共享查询范围，计算指定条件下的全量条数。 */
    long listLedgerEntriesCount(@Param("tenant") long tenant, @Param("warehouseId") long warehouseId, @Param("skuId") long skuId);
    /** 取准确总量已核对后的页内安全投影。 */
    List<LedgerEntry> listLedgerEntriesAtOffset(@Param("tenant") long tenant, @Param("warehouseId") long warehouseId, @Param("skuId") long skuId, @Param("limit") int limit, @Param("offset") long offset);

    /** 业务已持余额锁后读取本次更新的快照，不使用旧一致性读。 */
    List<Balance> lockBalance(@Param("tenant") long tenant,@Param("warehouseId") long warehouseId,@Param("skuId") long skuId);
    /** 核验目标仓库归属，防止跨租户库存调整。 */
    Integer countWarehouse(@Param("tenant") long tenant, @Param("warehouseId") long warehouseId);

    /** 追加库存守恒流水，必须与余额修改共同提交。 */
    int insertLedger(@Param("tenant") long tenant, @Param("operationRef") String operationRef, @Param("warehouseId") long warehouseId, @Param("skuId") long skuId, @Param("operationType") String operationType, @Param("availableDelta") long availableDelta, @Param("reservedDelta") long reservedDelta, @Param("soldDelta") long soldDelta, @Param("totalDelta") long totalDelta, @Param("reason") String reason, @Param("actorKey") String actorKey);
    /** 管理查询在本租户的完整数据范围执行筛选，先过滤再按主键分页。 */
    List<Balance> listAdminBalances(@Param("tenant") long tenant,@Param("q") AdminQuery query);
    /** 查询总量使用与游标列表相同的租户及筛选条件。 */
    long listAdminBalancesCount(@Param("tenant") long tenant,@Param("q") AdminQuery query);
    /** 在服务端核对过总量后对同一查询范围取指定页。 */
    List<Balance> listAdminBalancesAtOffset(@Param("tenant") long tenant,@Param("q") AdminQuery query, @Param("offset") long offset);

}
