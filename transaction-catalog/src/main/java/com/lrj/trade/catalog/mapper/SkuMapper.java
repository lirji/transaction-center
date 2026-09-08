package com.lrj.trade.catalog.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.lrj.trade.catalog.model.*;

/** SkuService的持久化边界；SQL仅位于同名XML，事务由业务层统一编排。 */
@Mapper
public interface SkuMapper {
    /** 在持有商品锁时统计规格数量，防止并发突破上限。 */
    Long countSkus(@Param("tenant") long tenant, @Param("productId") long productId);

    /** 新增独立规格草稿，规格JSON通过绑定参数传入。 */
    int insertSku(@Param("tenant") long tenant, @Param("productId") long productId, @Param("skuNo") String skuNo, @Param("price") long price, @Param("currency") String currency, @Param("stockType") String stockType, @Param("specification") String specification);

    /** 读取租户内规格编号，禁止跨租户定位。 */
    String findSkuId(@Param("tenant") long tenant, @Param("skuNo") String skuNo);

    /** 只更新指定商品下的目标规格，不影响其他规格。 */
    int updateSku(@Param("price") long price, @Param("currency") String currency, @Param("stockType") String stockType, @Param("specification") String specification, @Param("tenant") long tenant, @Param("productId") long productId, @Param("skuId") long skuId);

    /** 后台有界读取规格草稿，版本为整体商品版本。 */
    List<Sku> listDraftSkus(@Param("tenant") long tenant, @Param("productId") long productId, @Param("after") long after, @Param("limit") int limit);

    /** 会员只读取已发布版本快照，不暴露未发布草稿。 */
    List<Sku> listPublishedSkus(@Param("tenant") long tenant, @Param("productId") long productId, @Param("after") long after, @Param("limit") int limit);

    /** 读取规格所属商品，所属关系创建后不变。 */
    List<Long> findProductIds(@Param("tenant") long tenant, @Param("skuId") long skuId);

    /** 共享锁当前已发布商品，报价读取必须参与业务事务。 */
    List<Long> lockPublishedRevision(@Param("tenant") long tenant, @Param("productId") long productId);

    /** 当前读不可变发布快照，避免等待发布后仍读到旧快照。 */
    List<Sku> lockPublishedSku(@Param("tenant") long tenant, @Param("skuId") long skuId, @Param("revision") long revision);

    /** 通过商品版本条件更新串行化规格编辑与发布。 */
    int advanceDraftVersion(@Param("tenant") long tenant, @Param("productId") long productId, @Param("expectedVersion") long expectedVersion);
    /** 仅CAS失败后当前锁读，区分不存在、过期版本与状态冲突。 */
    List<Long> lockMutationVersion(@Param("tenant") long tenant,@Param("productId") long productId);
}
