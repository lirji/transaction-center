CREATE TABLE tc_audit_operation_attempt (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '交易服务内尝试流水',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '经认证的来源租户',
  request_id CHAR(36) CHARACTER SET ascii NOT NULL COMMENT '服务端分配单次尝试标识',
  actor_key VARCHAR(64) NOT NULL COMMENT '可信身份摘要，禁止保存凭据',
  action_code VARCHAR(64) NOT NULL COMMENT '稳定动作编码',
  resource_id VARCHAR(64) NULL COMMENT '已验真且确认的对象引用',
  trace_id CHAR(32) CHARACTER SET ascii NULL COMMENT '经格式校验的追踪根标识',
  occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '入口事实时间UTC',
  policy_version SMALLINT NOT NULL COMMENT '创建时保留策略版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_tc_audit_request (request_id),
  KEY ix_tc_audit_attempt_scope (tenant_id,resource_id,occurred_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='交易审计请求尝试';

CREATE TABLE tc_audit_attempt_outcome (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '追加结果流水',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '可信来源租户',
  attempt_id BIGINT UNSIGNED NOT NULL COMMENT '来源尝试流水',
  event_ref VARCHAR(128) CHARACTER SET ascii NOT NULL COMMENT '稳定结果事件标识',
  result_code VARCHAR(24) NOT NULL COMMENT '稳定结果码',
  error_code VARCHAR(64) NULL COMMENT '稳定拒绝或失败代码，不含错误原文',
  resource_id VARCHAR(64) NULL COMMENT '结果确认后的可信对象引用',
  effect_id BIGINT UNSIGNED NULL COMMENT '原成功效果记录引用',
  recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '结果追加时间UTC',
  PRIMARY KEY (id),
  UNIQUE KEY uk_tc_audit_outcome_event (event_ref),
  KEY ix_tc_audit_outcome_scope (tenant_id,resource_id,recorded_at,id),
  KEY ix_tc_audit_outcome_attempt (tenant_id,attempt_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='交易审计后续结果与原效果关联';

ALTER TABLE tc_operation_audit
  ADD COLUMN audit_attempt_id BIGINT UNSIGNED NULL COMMENT '本次提交的请求尝试，历史或系统作业记录可为空',
  ADD COLUMN audit_order_ref VARCHAR(64) NULL COMMENT '实际原生订单号',
  ADD COLUMN audit_refund_display_no VARCHAR(64) NULL COMMENT '实际退款展示编号',
  ADD COLUMN audit_amount_minor BIGINT UNSIGNED NULL COMMENT '退款金额最小货币单位',
  ADD COLUMN audit_currency CHAR(3) NULL COMMENT '退款币种代码',
  ADD COLUMN audit_before_state VARCHAR(24) NULL COMMENT '领域变更前的确认状态',
  ADD COLUMN audit_after_state VARCHAR(24) NULL COMMENT '领域变更后的确认状态',
  ADD COLUMN audit_trace_id CHAR(32) CHARACTER SET ascii NULL COMMENT '入口或作业追踪根',
  ADD UNIQUE KEY uk_tc_audit_effect_attempt (audit_attempt_id),
  ADD KEY ix_tc_audit_order (tenant_id,audit_order_ref,id);
