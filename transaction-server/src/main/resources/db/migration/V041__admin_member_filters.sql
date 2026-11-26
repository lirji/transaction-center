-- 会员状态与注册时间使用独立范围索引；消费事实仍由原投影读取。
ALTER TABLE tc_member
 ADD INDEX ix_admin_member_state(tenant_id,status,id) COMMENT '本租户会员状态游标',
 ADD INDEX ix_admin_member_registration(tenant_id,registered_at,id) COMMENT '本租户会员注册时间窗口',
 ALGORITHM=INPLACE, LOCK=NONE;
