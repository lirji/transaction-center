CREATE TABLE tc_payment_callback (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部回调接收主键',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '验签并核对商户绑定后的可信租户',
  callback_no VARCHAR(64) NOT NULL COMMENT '本地持久接收编号及后台任务业务引用',
  attempt_id BIGINT UNSIGNED NOT NULL COMMENT '关联不可变支付尝试主键',
  channel_code VARCHAR(64) NOT NULL COMMENT '验签通过的渠道类型',
  channel_merchant_no VARCHAR(255) NOT NULL COMMENT '已与支付尝试核对的渠道收款主体',
  provider_event_id VARCHAR(255) NOT NULL COMMENT '渠道事件号或合同确定性事件键',
  request_no VARCHAR(64) NOT NULL COMMENT '原渠道请求号，处理恢复不能换号',
  observation_kind VARCHAR(16) NOT NULL COMMENT '可信收款或可靠不可支付关闭事实',
  provider_transaction_no VARCHAR(255) NULL COMMENT '真实扣款交易号，关闭事实为空',
  amount BIGINT UNSIGNED NULL COMMENT '实际收款最小币种单位，关闭事实为空',
  currency CHAR(3) NULL COMMENT '实际资金币种，关闭事实为空',
  provider_paid_at DATETIME(3) NULL COMMENT '渠道扣款UTC时间，不用于重选首单',
  payload_hash BINARY(32) NOT NULL COMMENT '必要原始报文的SHA256摘要，事件冲突不得覆盖',
  fact_hash CHAR(64) NOT NULL COMMENT '可信解析结果的规范摘要，与认证密文绑定',
  payload_cipher MEDIUMBLOB NOT NULL COMMENT '必要原文的AES认证密文，含随机Nonce及认证标签',
  key_version VARCHAR(64) NOT NULL COMMENT '外部注入的报文加密密钥版本，轮换后保留历史解密能力',
  signature_version VARCHAR(64) NOT NULL COMMENT '可信适配器实际验签的证书或密钥版本',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '待处理已处理或人工核对状态',
  received_at DATETIME(3) NOT NULL COMMENT '主库首次持久接收UTC时间',
  processed_at DATETIME(3) NULL COMMENT '业务事实处理提交UTC时间，未完成为空',
  last_error VARCHAR(64) NULL COMMENT '最近脱敏错误分类码，不记录原文或密钥',
  created_at DATETIME(3) NOT NULL COMMENT '主库UTC创建时间',
  updated_at DATETIME(3) NOT NULL COMMENT '主库UTC最近处理状态更新时间',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '状态更新递增版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_callback_no (tenant_id,callback_no),
  UNIQUE KEY uk_callback_event (channel_code,channel_merchant_no,provider_event_id),
  KEY idx_callback_pending (tenant_id,status,received_at,id),
  CONSTRAINT fk_callback_attempt FOREIGN KEY (tenant_id,attempt_id) REFERENCES tc_payment_attempt(tenant_id,id),
  CONSTRAINT chk_callback_kind CHECK (
    (observation_kind='RECEIVED' AND provider_transaction_no IS NOT NULL AND amount IS NOT NULL AND amount>0
      AND amount<=9007199254740991 AND currency IS NOT NULL AND currency REGEXP '^[A-Z]{3}$' AND provider_paid_at IS NOT NULL)
    OR (observation_kind='CLOSED' AND provider_transaction_no IS NULL AND amount IS NULL AND currency IS NULL AND provider_paid_at IS NULL)),
  CONSTRAINT chk_callback_status CHECK (
    (status='PENDING' AND processed_at IS NULL AND last_error IS NULL)
    OR (status='PROCESSED' AND processed_at IS NOT NULL AND last_error IS NULL)
    OR (status='REVIEW' AND processed_at IS NULL AND last_error IS NOT NULL)),
  CONSTRAINT chk_callback_error CHECK (last_error IS NULL OR last_error REGEXP '^[A-Z][A-Z0-9_]{0,63}$'),
  CONSTRAINT chk_callback_cipher CHECK (OCTET_LENGTH(payload_cipher) BETWEEN 29 AND 65564)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='验签后支付回调耐久密文接收表';

-- V008自动生成约束名已在同版本MySQL核对；新增类型不改写已有任务历史。
ALTER TABLE tc_job_lease DROP CHECK tc_job_lease_chk_1,
  ADD CONSTRAINT chk_job_type CHECK (job_type IN ('PAYMENT_RECOVER','PAYMENT_CALLBACK'));
