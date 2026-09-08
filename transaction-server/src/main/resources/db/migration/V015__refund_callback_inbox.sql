CREATE TABLE tc_refund_callback (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部回调接收主键',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '验签并核对商户绑定后的可信租户',
  callback_no VARCHAR(64) NOT NULL COMMENT '本地持久接收编号及后台任务业务引用',
  refund_no VARCHAR(64) NOT NULL COMMENT '关联不可变退款申请编号',
  channel_code VARCHAR(64) NOT NULL COMMENT '验签通过的渠道类型',
  channel_merchant_no VARCHAR(255) NOT NULL COMMENT '已与原收款核对的渠道商户主体',
  provider_event_id VARCHAR(255) NOT NULL COMMENT '渠道事件号或合同确定性事件键',
  merchant_refund_no VARCHAR(64) NOT NULL COMMENT '原渠道请求号，处理恢复不能换号',
  observation_kind VARCHAR(16) NOT NULL COMMENT '可信退款成功或永久不可再成功终态',
  provider_transaction_no VARCHAR(255) NOT NULL COMMENT '证据中的原扣款号，异常归属仍保留待核对',
  provider_refund_no VARCHAR(255) NULL COMMENT '渠道真实退款号，可靠失败证据为空',
  amount BIGINT UNSIGNED NULL COMMENT '实际退款最小币种单位，可靠失败为空',
  currency CHAR(3) NULL COMMENT '实际退款币种，可靠失败为空',
  occurred_at DATETIME(3) NULL COMMENT '渠道退款事实UTC时间，统一毫秒精度',
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
  UNIQUE KEY uk_refund_callback_no (tenant_id,callback_no),
  UNIQUE KEY uk_refund_callback_event (channel_code,channel_merchant_no,provider_event_id),
  KEY idx_refund_callback_pending (tenant_id,status,received_at,id),
  CONSTRAINT fk_refund_callback_application FOREIGN KEY (tenant_id,refund_no) REFERENCES tc_refund(tenant_id,refund_no),
  CONSTRAINT chk_refund_callback_kind CHECK (
    (observation_kind='SUCCEEDED' AND provider_refund_no IS NOT NULL AND amount IS NOT NULL AND amount>0
      AND amount<=9007199254740991 AND currency IS NOT NULL AND currency REGEXP '^[A-Z]{3}$' AND occurred_at IS NOT NULL)
    OR (observation_kind='FAILED_FINAL' AND provider_refund_no IS NULL AND amount IS NULL AND currency IS NULL AND occurred_at IS NOT NULL)),
  CONSTRAINT chk_refund_callback_status CHECK (
    (status='PENDING' AND processed_at IS NULL AND last_error IS NULL)
    OR (status='PROCESSED' AND processed_at IS NOT NULL AND last_error IS NULL)
    OR (status='REVIEW' AND processed_at IS NULL AND last_error IS NOT NULL)),
  CONSTRAINT chk_refund_callback_error CHECK (last_error IS NULL OR last_error REGEXP '^[A-Z][A-Z0-9_]{0,63}$'),
  CONSTRAINT chk_refund_callback_cipher CHECK (OCTET_LENGTH(payload_cipher) BETWEEN 29 AND 65564)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='验真后退款回调耐久密文接收表';


-- 只新增退款证据消费类型；主动退款恢复在后续独立类型中实现。
ALTER TABLE tc_job_lease DROP CHECK chk_job_type,
  MODIFY COLUMN job_type VARCHAR(64) NOT NULL COMMENT '白名单任务类型，支付恢复回调订单解析或退款回调',
  ADD CONSTRAINT chk_job_type CHECK (job_type IN ('PAYMENT_RECOVER','PAYMENT_CALLBACK','ORDER_RESOLVE','REFUND_CALLBACK'));
