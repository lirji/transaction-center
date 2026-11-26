-- 审批截止查询只增加本租户索引，不改退款记录。
ALTER TABLE tc_refund
 ADD INDEX ix_admin_refund_approval_deadline(tenant_id,approval_deadline,id) COMMENT '本租户审批截止时间窗口',
 ALGORITHM=INPLACE, LOCK=NONE;
