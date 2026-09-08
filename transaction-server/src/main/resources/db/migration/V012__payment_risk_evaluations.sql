CREATE TABLE tc_payment_risk (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '风险评估历史主键，追加轮次不覆盖旧证据',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '支付尝试所属可信租户',
  evaluation_no VARCHAR(64) NOT NULL COMMENT '稳定风控评估幂等编号',
  attempt_id BIGINT UNSIGNED NOT NULL COMMENT '支付尝试主键',
  request_no VARCHAR(64) NOT NULL COMMENT '冻结的渠道请求号',
  order_id BIGINT UNSIGNED NOT NULL COMMENT '订单主键快照',
  member_id BIGINT UNSIGNED NOT NULL COMMENT '交易会员主键快照，不冒充银行账号',
  channel_code VARCHAR(64) NOT NULL COMMENT '冻结的支付渠道类型',
  channel_merchant_no VARCHAR(255) NOT NULL COMMENT '冻结的渠道收款主体',
  amount BIGINT UNSIGNED NOT NULL COMMENT '冻结的最小币种单位金额',
  currency CHAR(3) NOT NULL COMMENT '冻结币种',
  input_hash CHAR(64) NOT NULL COMMENT '版本化风险输入的规范摘要',
  state VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '待评估或不可变允许拒绝结果',
  decision_id VARCHAR(128) NULL COMMENT '可信风控决策编号',
  rule_version VARCHAR(128) NULL COMMENT '实际评估规则版本',
  model_version VARCHAR(128) NULL COMMENT '实际模型版本，纯规则策略显式填NONE',
  valid_until DATETIME(3) NULL COMMENT '允许创建支付的UTC有效期限，拒绝和待定为空',
  requested_at DATETIME(3) NOT NULL COMMENT '评估输入冻结UTC时间',
  deadline DATETIME(3) NOT NULL COMMENT '本轮评估最晚接收UTC时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_risk_evaluation (tenant_id,evaluation_no),
  UNIQUE KEY uk_risk_attempt_evaluation (tenant_id,attempt_id,evaluation_no),
  KEY idx_risk_latest (tenant_id,request_no,id),
  CONSTRAINT fk_risk_attempt FOREIGN KEY (tenant_id,attempt_id) REFERENCES tc_payment_attempt(tenant_id,id),
  CONSTRAINT chk_risk_amount CHECK (amount>0 AND amount<=9007199254740991),
  CONSTRAINT chk_risk_currency CHECK (currency REGEXP '^[A-Z]{3}$'),
  CONSTRAINT chk_risk_time CHECK (deadline>requested_at),
  CONSTRAINT chk_risk_state CHECK (
    (state='PENDING' AND decision_id IS NULL AND rule_version IS NULL AND model_version IS NULL AND valid_until IS NULL)
    OR (state='ALLOW' AND decision_id IS NOT NULL AND rule_version IS NOT NULL AND model_version IS NOT NULL AND valid_until IS NOT NULL
        AND valid_until>requested_at AND valid_until<=deadline)
    OR (state='DENY' AND decision_id IS NOT NULL AND rule_version IS NOT NULL AND model_version IS NOT NULL AND valid_until IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='支付风控评估输入及不可变结果历史';

-- 先持订单/尝试锁再追加和切换指针；不用对尚无评估的索引范围加间隙锁。
ALTER TABLE tc_payment_attempt
  ADD COLUMN risk_evaluation_no VARCHAR(64) NULL COMMENT '当前风险评估轮次，必须绑定本租户本支付尝试',
  ADD CONSTRAINT fk_attempt_risk FOREIGN KEY (tenant_id,id,risk_evaluation_no)
    REFERENCES tc_payment_risk(tenant_id,attempt_id,evaluation_no);
