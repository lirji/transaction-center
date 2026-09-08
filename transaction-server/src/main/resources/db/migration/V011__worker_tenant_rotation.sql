-- 全租户发现通过固定任务类型、可运行状态及租户范围定位，不扫描历史终态或热点租户所有任务。
ALTER TABLE tc_job_lease ADD INDEX idx_job_tenant_rotation(job_type,runnable,tenant_id)
  COMMENT '后台任务按类型和可运行状态进行租户游标轮转';
