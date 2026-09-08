-- 各租户分页进度与候选入队同事务；全局轮转不承担持久水位。
CREATE TABLE tc_outbox_maintenance (
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属租户及维护唯一主键',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次发现UTC时间',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近维护进度更新时间',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '维护状态更新版本',
  cursor_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '本轮已原子处理的最大事件ID',
  round_upper_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '本轮开始时已提交最大ID，不是提交时间水位',
  round_active BOOLEAN NOT NULL DEFAULT FALSE COMMENT '本轮是否尚未扫描完成',
  round_started_at DATETIME(3) NULL COMMENT '本轮开始UTC时间',
  scanned_count BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '本轮已扫描可见记录数，含v0和终态',
  candidate_count BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '本轮符合入队条件的记录数，不等于新增任务数',
  completed_rounds BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已完成的有限扫描轮数',
  last_completed_at DATETIME(3) NULL COMMENT '最近一轮完成UTC时间',
  last_upper_id BIGINT UNSIGNED NULL COMMENT '最近完成轮的固定ID上界',
  last_scanned_count BIGINT UNSIGNED NULL COMMENT '最近完成轮实际可见扫描数',
  last_candidate_count BIGINT UNSIGNED NULL COMMENT '最近完成轮候选数量',
  next_round_at DATETIME(3) NOT NULL COMMENT '最早可开始下一轮的数据库UTC时间',
  PRIMARY KEY(tenant_id),
  CONSTRAINT chk_outbox_maintenance_cursor CHECK (cursor_id<=round_upper_id),
  CONSTRAINT chk_outbox_maintenance_count CHECK (candidate_count<=scanned_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='历史V1发布任务补漏的租户持久分页进度';

-- 固定类型状态的最老任务与有界计数均通过覆盖索引获取。
ALTER TABLE tc_job_lease ADD KEY ix_job_publication_age(job_type,status,created_at,id);
