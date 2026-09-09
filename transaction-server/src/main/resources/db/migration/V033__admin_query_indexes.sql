-- 管理读取只追加索引，不改资金事实、历史迁移或既有调度索引。
-- 租户前缀保证查询不会依赖跨租户主键扫描；高选择性状态使用固定组合。
ALTER TABLE tc_order
 ADD INDEX ix_admin_order_status(tenant_id,status,id) COMMENT '本租户订单状态游标',
 ADD INDEX ix_admin_order_financial(tenant_id,financial_status,id) COMMENT '本租户订单财务状态游标',
 ADD INDEX ix_admin_order_created(tenant_id,created_at,id) COMMENT '本租户订单创建时间窗口';
ALTER TABLE tc_payment_intent
 ADD INDEX ix_admin_payment_status(tenant_id,status,id) COMMENT '本租户支付状态游标',
 ADD INDEX ix_admin_payment_member(tenant_id,member_id,id) COMMENT '本租户会员支付查询',
 ADD INDEX ix_admin_payment_created(tenant_id,created_at,id) COMMENT '本租户支付创建时间窗口';
ALTER TABLE tc_payment_attempt
 ADD INDEX ix_admin_attempt_page(tenant_id,intent_id,id) COMMENT '支付意图内尝试主键游标';
ALTER TABLE tc_payment_receipt
 ADD INDEX ix_admin_receipt_disposition(tenant_id,disposition,id) COMMENT '本租户收款分类游标',
 ADD INDEX ix_admin_receipt_order(tenant_id,order_id,id) COMMENT '本租户订单所有实收记录',
 ADD INDEX ix_admin_receipt_created(tenant_id,created_at,id) COMMENT '本租户收款登记时间窗口';
ALTER TABLE tc_return_request
 ADD INDEX ix_admin_return_page(tenant_id,id) COMMENT '退货申请列表包括未验收记录',
 ADD INDEX ix_admin_return_status(tenant_id,status,id) COMMENT '本租户退货申请状态游标',
 ADD INDEX ix_admin_return_order(tenant_id,order_id,id) COMMENT '本租户订单退货申请',
 ADD INDEX ix_admin_return_created(tenant_id,created_at,id) COMMENT '本租户退货申请创建时间窗口';
ALTER TABLE tc_quote
 ADD INDEX ix_admin_quote_created(tenant_id,created_at,id) COMMENT '本租户报价创建时间窗口';
ALTER TABLE tc_refund
 ADD INDEX ix_admin_refund_page(tenant_id,id) COMMENT '退款ALL查询主键游标',
 ADD INDEX ix_admin_refund_created(tenant_id,created_at,id) COMMENT '本租户退款申请时间窗口';
-- 已有ix_job_publication_queue覆盖tenant_id/job_type/status/id，不重复建立。
ALTER TABLE tc_job_lease
 ADD INDEX ix_admin_job_page(tenant_id,id) COMMENT '本租户所有任务主键游标',
 ADD INDEX ix_admin_job_status(tenant_id,status,id) COMMENT '本租户任务状态游标',
 ADD INDEX ix_admin_job_created(tenant_id,created_at,id) COMMENT '本租户任务创建时间窗口';
