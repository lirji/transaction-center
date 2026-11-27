package com.lrj.trade.catalog.mapper;

import com.lrj.trade.core.query.AdminQuery;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.lrj.trade.catalog.model.*;

/** CatalogService的持久化边界；SQL仅位于同名XML，事务由业务层统一编排。 */
@Mapper
public interface CatalogMapper {
    /** 新增商品草稿，商品业务编号在租户内唯一。 */
    int insertProduct(@Param("tenant") long tenant, @Param("productNo") String productNo, @Param("name") String name);

    /** 读取本事务创建的商品编号。 */
    Long findProductId(@Param("tenant") long tenant, @Param("productNo") String productNo);

    /** 兼容原创建入口，新增首个主规格。 */
    int insertPrimarySku(@Param("tenant") long tenant, @Param("productId") long productId, @Param("skuNo") String skuNo, @Param("price") long price, @Param("currency") String currency);

    /** 以状态及版本条件更新草稿，禁止直接编辑线上商品。 */
    int updateDraftName(@Param("name") String name, @Param("tenant") long tenant, @Param("productId") long productId, @Param("expectedVersion") long expectedVersion);

    /** 只更新最早创建的主规格，不能覆盖其他规格价格。 */
    int updatePrimarySkuPrice(@Param("price") long price, @Param("currency") String currency, @Param("tenant") long tenant, @Param("productId") long productId);

    /** 以版本条件发布并推进快照版本，与快照写入同事务。 */
    int publishProduct(@Param("tenant") long tenant, @Param("productId") long productId, @Param("expectedVersion") long expectedVersion);

    /** 复制全量规格为不可变快照，不覆盖历史发布版本。 */
    int insertPublishedSnapshots(@Param("tenant") long tenant, @Param("productId") long productId);

    /** 以状态及版本条件下架，不删除历史快照。 */
    int unpublishProduct(@Param("tenant") long tenant, @Param("productId") long productId, @Param("expectedVersion") long expectedVersion);

    /** 每商品只取主规格，避免多规格破坏商品游标分页。 */
    List<Product> listProducts(@Param("tenant") long tenant, @Param("after") long after, @Param("admin") boolean admin, @Param("limit") int limit);

    /** 读取主规格展示视图，会员只允许读取已发布商品。 */
    List<Product> findProduct(@Param("tenant") long tenant, @Param("productId") long productId, @Param("admin") boolean admin);

    /** 按租户及发布要求核验规格，供库存公开端口使用。 */
    Integer countEligibleSku(@Param("tenant") long tenant, @Param("skuId") long skuId, @Param("published") boolean published);
    /** 仅CAS失败后当前锁读，区分不存在、过期版本与状态冲突。 */
    List<Long> lockMutationVersion(@Param("tenant") long tenant,@Param("productId") long productId);
    /** 管理查询在本租户的完整数据范围执行筛选，先过滤再按主键分页。 */
    List<AdminProduct> listAdminProducts(@Param("tenant") long tenant,@Param("q") AdminQuery query);
    /** 查询总量使用与游标列表相同的租户及筛选条件。 */
    long listAdminProductsCount(@Param("tenant") long tenant,@Param("q") AdminQuery query);
    /** 在服务端核对过总量后对同一查询范围取指定页。 */
    List<AdminProduct> listAdminProductsAtOffset(@Param("tenant") long tenant,@Param("q") AdminQuery query, @Param("offset") long offset);

    /** 更新运营展示元数据，已冻结报价和 SKU 版本保持各自原有事实。 */
    int setImage(@Param("tenant") long tenant, @Param("product") long product, @Param("version") long version,
            @Param("imageUrl") String imageUrl);
}
