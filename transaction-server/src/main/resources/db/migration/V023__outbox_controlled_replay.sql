-- 新一轮发布预算不重置全局fencing，永久凭证不能随命令缓存淘汰。
ALTER TABLE tc_outbox
  ADD COLUMN publish_attempt_base BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '本轮开始时累计领取次数，重放仅推进基数',
  ADD COLUMN replay_count BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已执行的后台重放轮数，不含自动重试',
  ADD COLUMN last_replayed_at DATETIME(3) NULL COMMENT '最近后台受控重放UTC时间',
  ADD KEY ix_outbox_tenant_state (tenant_id,status,id);
CREATE TABLE tc_outbox_replay_receipt (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部凭证主键',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属租户，查询与操作均限定',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次请求UTC时间',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新UTC时间',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '凭证完成版本',
  actor_key CHAR(64) NOT NULL COMMENT '已认证操作主体身份摘要',
  request_key VARCHAR(128) NOT NULL COMMENT '主体内永久幂等请求键',
  event_id CHAR(36) NOT NULL COMMENT '原始发布事件编号，重放不得改换',
  request_hash CHAR(64) NOT NULL COMMENT '事件版本原因与工单引用的规范请求摘要',
  reason VARCHAR(255) NOT NULL COMMENT '人工重放原因，不保存认证令牌或事件载荷',
  ticket_ref VARCHAR(128) NOT NULL COMMENT '运营关联工单引用，不代表外部工单已自动创建',
  result_version BIGINT UNSIGNED NULL COMMENT '首次重放后的事件版本',
  result_replay_count BIGINT UNSIGNED NULL COMMENT '首次重放后的轮数',
  completed_at DATETIME(3) NULL COMMENT '首次成功受控重放UTC时间',
  PRIMARY KEY(id),
  UNIQUE KEY uk_outbox_replay_request(tenant_id,actor_key,request_key),
  KEY ix_outbox_replay_event(tenant_id,event_id,id),
  CONSTRAINT chk_outbox_replay_result CHECK ((result_version IS NULL AND result_replay_count IS NULL AND completed_at IS NULL) OR (result_version IS NOT NULL AND result_replay_count>0 AND result_replay_count IS NOT NULL AND completed_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='后台消息重放永久请求凭证，不随普通幂等缓存清理';

-- 按租户与状态展示隔离发布任务，不扫描其他资金任务。
ALTER TABLE tc_job_lease ADD KEY ix_job_publication_queue(tenant_id,job_type,status,id);
