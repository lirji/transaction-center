-- 成功退款由退款自己的确认时间归期，索引仅改变期间读取路径。
ALTER TABLE tc_payment_refund_hold
 ADD INDEX ix_operations_refund_confirmed(tenant_id,currency,confirmed_at,id) COMMENT '本租户分币种退款确认期间',
 ALGORITHM=INPLACE, LOCK=NONE;
