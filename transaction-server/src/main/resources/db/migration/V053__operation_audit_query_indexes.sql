-- 先按可信租户定位，再以事件时间和主键反向读取；匹配筛选时沿用同一顺序。
ALTER TABLE tc_operation_audit
  ADD KEY ix_tc_audit_tenant_time (tenant_id,created_at,id),
  ADD KEY ix_tc_audit_tenant_type_time (tenant_id,resource_type,created_at,id),
  ADD KEY ix_tc_audit_tenant_resource_time (tenant_id,resource_id,created_at,id);

ALTER TABLE tc_audit_attempt_outcome
  ADD KEY ix_tc_audit_outcome_tenant_time (tenant_id,recorded_at,id);
