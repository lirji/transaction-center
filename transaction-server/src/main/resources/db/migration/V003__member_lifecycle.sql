-- 增量增加会员生命周期能力；不修改已执行的V001/V002，不删除原业务数据。
ALTER TABLE tc_member ADD CONSTRAINT ck_member_status CHECK (status IN ('ACTIVE','FROZEN'));

CREATE TABLE tc_consent_document (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键，对外以字符串表示',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属租户，所有访问必须限定租户',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建UTC时间，毫秒精度',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新UTC时间，事实记录不允许业务覆盖',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '记录版本，追加型事实保持不变',
  purpose VARCHAR(64) NOT NULL COMMENT '条款用途，不与条款版本混用',
  document_version VARCHAR(64) NOT NULL COMMENT '该用途下的不可变条款版本',
  title VARCHAR(200) NOT NULL COMMENT '条款展示标题',
  content_url VARCHAR(1000) NOT NULL COMMENT '公开HTTPS条款内容地址，仅保存不由后端抓取',
  content_hash CHAR(64) NOT NULL COMMENT '发布内容的SHA256摘要，客户端呈现内容需与之相符',
  UNIQUE KEY uk_consent_document (tenant_id,purpose,document_version),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tc_consent_document_tenant_id (tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='已发布的不可变会员条款版本表';

CREATE TABLE tc_member_consent (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键，对外以字符串表示',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属租户，所有访问必须限定租户',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建UTC时间，毫秒精度',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新UTC时间，事实记录不允许业务覆盖',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '记录版本，追加型事实保持不变',
  member_id BIGINT UNSIGNED NOT NULL COMMENT '执行动作的会员主键',
  document_id BIGINT UNSIGNED NOT NULL COMMENT '已发布且不可变的条款版本主键',
  purpose VARCHAR(64) NOT NULL COMMENT '同意用途快照',
  document_version VARCHAR(64) NOT NULL COMMENT '条款版本快照',
  action VARCHAR(16) NOT NULL COMMENT 'GRANT表示同意，WITHDRAW表示撤回该用途最近一次同意',
  member_version BIGINT UNSIGNED NOT NULL COMMENT '执行动作后的会员版本，保证同一会员事件有序',
  source_request_id VARCHAR(128) NOT NULL COMMENT '该会员的稳定幂等请求键',
  UNIQUE KEY uk_member_consent_request (tenant_id,member_id,source_request_id),
  UNIQUE KEY uk_member_consent_version (tenant_id,member_id,member_version),
  KEY ix_member_consent_history (tenant_id,member_id,id),
  KEY ix_member_consent_latest (tenant_id,member_id,purpose,member_version),
  FOREIGN KEY (tenant_id,member_id) REFERENCES tc_member (tenant_id,id),
  FOREIGN KEY (tenant_id,document_id) REFERENCES tc_consent_document (tenant_id,id),
  CHECK (action IN ('GRANT','WITHDRAW')),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tc_member_consent_tenant_id (tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='会员同意或撤回的不可变事件表';

CREATE TABLE tc_operation_audit (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键，对外以字符串表示',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属租户，所有访问必须限定租户',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建UTC时间，毫秒精度',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新UTC时间，事实记录不允许业务覆盖',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '记录版本，追加型事实保持不变',
  actor_key VARCHAR(64) NOT NULL COMMENT '可信签发方与主体摘要，不记录密码或令牌',
  action VARCHAR(64) NOT NULL COMMENT '业务动作类型',
  resource_type VARCHAR(64) NOT NULL COMMENT '被操作的资源类型',
  resource_id VARCHAR(64) NOT NULL COMMENT '被操作资源的主键或稳定编号',
  reason VARCHAR(255) NOT NULL COMMENT '操作原因，不应填入个人敏感明文',
  before_version BIGINT UNSIGNED NOT NULL COMMENT '变更前业务版本，创建时为零',
  after_version BIGINT UNSIGNED NOT NULL COMMENT '变更后业务版本',
  KEY ix_audit_resource (tenant_id,resource_type,resource_id,id),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tc_operation_audit_tenant_id (tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='高风险业务操作的不可变审计记录表';

