-- 库存余额按规格或可售阈值读取，不触发交易预占和盘点。
ALTER TABLE tc_inventory_balance
 ADD INDEX ix_admin_inventory_sku(tenant_id,sku_id,id) COMMENT '本租户指定SKU的库存余额',
 ADD INDEX ix_admin_inventory_available(tenant_id,available,id) COMMENT '本租户可售数量阈值查询',
 ALGORITHM=INPLACE, LOCK=NONE;
