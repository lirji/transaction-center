-- 实收确认时间查询只增加本租户索引，不改收款记录。
ALTER TABLE tc_payment_receipt
 ADD INDEX ix_admin_receipt_confirmed(tenant_id,confirmed_at,id) COMMENT '本租户实收确认时间窗口',
 ALGORITHM=INPLACE, LOCK=NONE;
