-- 商品精确名称搜索使用租户前缀，避免跨租户扫描。
ALTER TABLE tc_product
 ADD INDEX ix_admin_product_name(tenant_id,name,id) COMMENT '本租户精确名称及商品游标',
 ALGORITHM=INPLACE, LOCK=NONE;
