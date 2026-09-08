package com.lrj.trade.catalog;

/** 库存跨领域只访问此公开端口，不访问商品表或Mapper。 */
public interface CatalogPort {
    /** 验证租户SKU归属；预占要求已发布，调整库存允许尚未上架。 */
    void requireSku(long tenant, long sku, boolean mustBePublished);
}

