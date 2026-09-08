-- 可信审批成功受理证据与审批状态同事务提交；拒绝覆盖历史来源事件。
CREATE TABLE tc_refund_approval_receipt (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部证据主键',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '经认证的业务租户',
  receipt_no VARCHAR(64) NOT NULL COMMENT '内部审批证据接收编号',
  source_issuer VARCHAR(255) NOT NULL COMMENT '已验签回执签发方',
  source_subject VARCHAR(255) NOT NULL COMMENT '已认证流程服务主体',
  event_id VARCHAR(64) NOT NULL COMMENT '来源永久事件编号，不可复用',
  refund_no VARCHAR(64) NOT NULL COMMENT '原退款申请编号，业务事务核对归属',
  fact_hash CHAR(64) NOT NULL COMMENT '审批主体关联版本决定及证据的语义摘要',
  payload_hash CHAR(64) NOT NULL COMMENT '签名回执原文摘要',
  payload_cipher MEDIUMBLOB NOT NULL COMMENT '认证加密的签名回执，不保存明文JWT',
  key_version VARCHAR(64) NOT NULL COMMENT '证据加密历史密钥版本',
  created_at DATETIME(3) NOT NULL COMMENT '数据库首次受理UTC时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_approval_receipt_no (tenant_id,receipt_no),
  UNIQUE KEY uk_approval_source_event (source_issuer,source_subject,event_id),
  KEY ix_approval_refund (tenant_id,refund_no,id),
  CHECK (CHAR_LENGTH(fact_hash)=64 AND CHAR_LENGTH(payload_hash)=64),
  CHECK (OCTET_LENGTH(payload_cipher)>0 AND CHAR_LENGTH(key_version)>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='退款审批已成功受理的密文证据及来源事件永久去重表';
