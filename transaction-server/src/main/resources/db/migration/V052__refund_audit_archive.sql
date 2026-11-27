CREATE TABLE tc_refund_audit_archive_batch (
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '交易侧已核实的档案租户',
  batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定的档案对象批次',
  refund_id BIGINT UNSIGNED NOT NULL COMMENT '退款主键，便于精确核对来源',
  refund_no VARCHAR(64) NOT NULL COMMENT '写入时的可读退款号或旧系统退款号',
  schema_version SMALLINT UNSIGNED NOT NULL COMMENT '来源投影格式版本',
  facts_from DATETIME(3) NOT NULL COMMENT '归档中最早证据发生时间UTC',
  facts_until DATETIME(3) NOT NULL COMMENT '归档中最晚证据时间UTC',
  fact_count SMALLINT UNSIGNED NOT NULL COMMENT '固定对象中的可读证据行数',
  object_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户及批次固定的对象键',
  object_version VARCHAR(255) NULL COMMENT '已上传对象的精确存储版本',
  sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '压缩档案的稳定摘要',
  payload_bytes INT UNSIGNED NOT NULL COMMENT '压缩档案字节数',
  payload MEDIUMBLOB NULL COMMENT '待上传或回读期间保留的固定脱敏快照，验证通过即释放',
  state VARCHAR(16) NOT NULL COMMENT '领取、上传和校验阶段',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '批次创建时间UTC',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '批次状态更新时间UTC',
  pending_tenant BIGINT UNSIGNED GENERATED ALWAYS AS (
    CASE WHEN state IN('CLAIMED','UPLOADED') THEN tenant_id ELSE NULL END
  ) STORED COMMENT '同租户未核验的批次互斥，限制对象不可用时的未验证数据',
  PRIMARY KEY(tenant_id,batch_id),
  UNIQUE KEY uk_tc_refund_archive_pending(pending_tenant),
  UNIQUE KEY uk_tc_refund_archive_snapshot(tenant_id,refund_id,sha256),
  KEY ix_tc_refund_archive_pending(tenant_id,state,created_at,batch_id),
  CONSTRAINT ck_tc_refund_archive_state CHECK (
    (state='CLAIMED' AND object_version IS NULL AND payload IS NOT NULL)
    OR (state='UPLOADED' AND object_version IS NOT NULL AND payload IS NOT NULL)
    OR (state='VERIFIED' AND object_version IS NOT NULL AND payload IS NULL)
  ),
  CONSTRAINT ck_tc_refund_archive_content CHECK (fact_count BETWEEN 1 AND 200 AND payload_bytes BETWEEN 1 AND 524288
    AND facts_from<=facts_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='交易退款脱敏操作证据归档及精确对象版本';

CREATE TABLE tc_refund_audit_archive_grant (
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '恢复授权所属交易租户',
  ticket_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '外部审批工单号',
  batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '允许回读的单个档案批次',
  actor_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '允许查阅的认证身份摘要',
  approved_by CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不同于申请人的独立审批身份摘要',
  expires_at DATETIME(3) NOT NULL COMMENT '临时恢复与读取最晚时间UTC',
  revoked_at DATETIME(3) NULL COMMENT '审批撤销时间UTC',
  PRIMARY KEY(tenant_id,ticket_id),
  CONSTRAINT fk_tc_refund_archive_grant_batch FOREIGN KEY(tenant_id,batch_id)
    REFERENCES tc_refund_audit_archive_batch(tenant_id,batch_id),
  CONSTRAINT ck_tc_refund_archive_grant_actor CHECK(actor_key<>approved_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='交易退款归档的租户内独立恢复授权';

CREATE TABLE tc_refund_audit_archive_restored (
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '临时查询区租户',
  ticket_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '单独授权的恢复工单',
  batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '临时副本实际恢复的档案批次',
  payload JSON NOT NULL COMMENT '精确版本回读并核验后的脱敏证据投影',
  expires_at DATETIME(3) NOT NULL COMMENT '恢复值的最晚展示时间UTC',
  PRIMARY KEY(tenant_id,ticket_id),
  KEY ix_tc_refund_archive_restored_expiry (tenant_id,expires_at,ticket_id),
  CONSTRAINT fk_tc_refund_archive_restored_grant FOREIGN KEY(tenant_id,ticket_id)
    REFERENCES tc_refund_audit_archive_grant(tenant_id,ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='受控工单内临时获取的交易退款审计档案';

ALTER TABLE tc_refund_callback
  ADD KEY ix_tc_refund_archive_callback (tenant_id,refund_no,id) COMMENT '按精确退款定位有界验真渠道引用';
