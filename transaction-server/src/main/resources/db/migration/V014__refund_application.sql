-- 冻结明细归属供退款组件额度复合外键验证；历史版本不改写。
ALTER TABLE tc_order_item ADD UNIQUE KEY uk_order_item_refund_ref (tenant_id,order_id,id);

CREATE TABLE tc_refund_policy (
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '策略所属可信租户',
  currency CHAR(3) NOT NULL COMMENT '免审适用币种，不能跨币种比较门槛',
  auto_approve_limit BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '同一订单成功及在途累计退款免审上限，零表示必须审批',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '策略乐观版本，申请冻结实际采用版本与门槛',
  created_at DATETIME(3) NOT NULL COMMENT '配置首次创建UTC时间',
  updated_at DATETIME(3) NOT NULL COMMENT '配置最近变更UTC时间',
  PRIMARY KEY (tenant_id),
  CONSTRAINT chk_refund_policy_currency CHECK (currency REGEXP '^[A-Z]{3}$'),
  CONSTRAINT chk_refund_policy_limit CHECK (auto_approve_limit<=9007199254740991)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='租户退款免审政策，版本变更审计且申请保留快照';

CREATE TABLE tc_refund (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '退款申请内部主键',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属可信租户',
  refund_no VARCHAR(64) NOT NULL COMMENT '永久退款业务编号',
  source_request_id VARCHAR(128) NOT NULL COMMENT '申请主体永久请求号，不依赖短期HTTP幂等记录',
  applicant_key CHAR(64) NOT NULL COMMENT '可信签发方与申请主体联合摘要',
  member_id BIGINT UNSIGNED NOT NULL COMMENT '原购买会员主键',
  order_id BIGINT UNSIGNED NOT NULL COMMENT '原订单内部主键',
  order_no VARCHAR(64) NOT NULL COMMENT '原订单业务编号快照',
  receipt_id BIGINT UNSIGNED NOT NULL COMMENT '原正常实收主键',
  receipt_no VARCHAR(64) NOT NULL COMMENT '原正常实收编号快照',
  merchant_refund_no VARCHAR(64) NOT NULL COMMENT '永久原渠道退款请求号，不随重试改变',
  amount BIGINT UNSIGNED NOT NULL COMMENT '本次申请最小币种单位金额',
  currency CHAR(3) NOT NULL COMMENT '冻结原实收币种',
  reason VARCHAR(255) NOT NULL COMMENT '退款业务原因，不应填写敏感个人明文',
  request_hash CHAR(64) NOT NULL COMMENT '规范化请求摘要，永久号不能换内容',
  status VARCHAR(24) NOT NULL COMMENT '待审批就绪已派发成功拒绝可靠失败或审批到期',
  policy_version BIGINT UNSIGNED NOT NULL COMMENT '冻结的退款政策版本，零表示未配置',
  policy_limit BIGINT UNSIGNED NOT NULL COMMENT '冻结的累计免审金额上限',
  approval_ref VARCHAR(64) NULL COMMENT '待审批关联号，可信结果必须精确匹配',
  approval_deadline DATETIME(3) NULL COMMENT '审批结果受理截止UTC时间，不自动释放未知资金',
  approval_decision_version BIGINT UNSIGNED NULL COMMENT '唯一受理的外部审批决定版本',
  approval_actor_key CHAR(64) NULL COMMENT '审批人可信身份摘要，不得与申请人相同',
  approval_decision VARCHAR(16) NULL COMMENT '已受理审批决定APPROVE或REJECT',
  approval_evidence VARCHAR(255) NULL COMMENT '审批证据引用，不保存签名密钥或原始个人信息',
  provider_refund_no VARCHAR(255) NULL COMMENT '可信成功后的渠道退款流水号',
  created_at DATETIME(3) NOT NULL COMMENT '申请创建UTC时间',
  updated_at DATETIME(3) NOT NULL COMMENT '申请最近变更UTC时间',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '退款应用状态版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_refund_no (tenant_id,refund_no),
  UNIQUE KEY uk_refund_source (tenant_id,applicant_key,source_request_id),
  UNIQUE KEY uk_refund_merchant_no (tenant_id,merchant_refund_no),
  UNIQUE KEY uk_refund_order_ref (tenant_id,id,order_id),
  UNIQUE KEY uk_refund_approval (tenant_id,approval_ref),
  KEY ix_refund_member (tenant_id,member_id,id),
  KEY ix_refund_order (tenant_id,order_id,id),
  KEY ix_refund_state (tenant_id,status,id),
  CONSTRAINT fk_refund_receipt FOREIGN KEY (tenant_id,receipt_id,order_id) REFERENCES tc_payment_receipt(tenant_id,id,order_id),
  CONSTRAINT fk_refund_member FOREIGN KEY (tenant_id,member_id) REFERENCES tc_member(tenant_id,id),
  CONSTRAINT chk_refund_amount CHECK (amount>0 AND amount<=9007199254740991 AND policy_limit<=9007199254740991),
  CONSTRAINT chk_refund_currency CHECK (currency REGEXP '^[A-Z]{3}$'),
  CONSTRAINT chk_refund_status CHECK (status IN ('PENDING_APPROVAL','READY','SUBMITTED','SUCCEEDED','REJECTED','FAILED','EXPIRED')),
  CONSTRAINT chk_refund_expired CHECK (status<>'EXPIRED' OR (approval_ref IS NOT NULL AND approval_decision_version IS NULL)),
  CONSTRAINT chk_refund_approval_pair CHECK ((approval_ref IS NULL AND approval_deadline IS NULL) OR (approval_ref IS NOT NULL AND approval_deadline IS NOT NULL)),
  CONSTRAINT chk_refund_pending CHECK (status<>'PENDING_APPROVAL' OR (approval_ref IS NOT NULL AND approval_decision_version IS NULL)),
  CONSTRAINT chk_refund_approved_state CHECK (
    status NOT IN ('READY','SUBMITTED','SUCCEEDED','FAILED')
    OR (approval_ref IS NULL AND policy_version>0 AND policy_limit>0)
    OR (approval_ref IS NOT NULL AND approval_decision_version IS NOT NULL AND approval_decision IS NOT NULL AND approval_decision='APPROVE')),
  CONSTRAINT chk_refund_rejected_state CHECK (status<>'REJECTED' OR (approval_decision_version IS NOT NULL AND approval_decision IS NOT NULL AND approval_decision='REJECT')),
  CONSTRAINT chk_refund_decision CHECK (
    (approval_decision_version IS NULL AND approval_actor_key IS NULL AND approval_decision IS NULL AND approval_evidence IS NULL)
    OR (approval_decision_version IS NOT NULL AND approval_decision_version>0 AND approval_actor_key IS NOT NULL
      AND approval_actor_key<>applicant_key AND approval_decision IS NOT NULL AND approval_decision IN ('APPROVE','REJECT')
      AND approval_evidence IS NOT NULL AND approval_ref IS NOT NULL)),
  CONSTRAINT chk_refund_provider CHECK ((status='SUCCEEDED' AND provider_refund_no IS NOT NULL) OR (status<>'SUCCEEDED' AND provider_refund_no IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='退款申请审批与执行聚合，资金由支付域Port维护';

CREATE TABLE tc_refund_component_budget (
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '组件额度所属可信租户',
  order_id BIGINT UNSIGNED NOT NULL COMMENT '冻结订单归属',
  component_key VARCHAR(64) NOT NULL COMMENT '规范化组件键，商品为GOODS加明细主键，其他为固定类型',
  component_type VARCHAR(16) NOT NULL COMMENT '退款归属GOODS商品FREIGHT运费TAX税',
  order_item_id BIGINT UNSIGNED NULL COMMENT '商品冻结明细主键，非商品为空',
  amount_limit BIGINT UNSIGNED NOT NULL COMMENT '首次使用时从订单冻结的本组件可退总额',
  reserved_amount BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '本组件进行中退款预占额',
  refunded_amount BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '本组件累计成功退款额',
  created_at DATETIME(3) NOT NULL COMMENT '额度快照创建UTC时间',
  updated_at DATETIME(3) NOT NULL COMMENT '额度最近变化UTC时间',
  PRIMARY KEY (tenant_id,order_id,component_key),
  CONSTRAINT fk_refund_budget_order FOREIGN KEY (tenant_id,order_id) REFERENCES tc_order(tenant_id,id),
  CONSTRAINT fk_refund_budget_item FOREIGN KEY (tenant_id,order_id,order_item_id) REFERENCES tc_order_item(tenant_id,order_id,id),
  CONSTRAINT chk_refund_budget_amount CHECK (amount_limit<=9007199254740991 AND reserved_amount+refunded_amount<=amount_limit),
  CONSTRAINT chk_refund_budget_type CHECK (
    (component_type='GOODS' AND order_item_id IS NOT NULL AND component_key=CONCAT('GOODS:',order_item_id))
    OR (component_type IN ('FREIGHT','TAX') AND order_item_id IS NULL AND component_key=component_type))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='退款域订单冻结组件额度，成功与预占不得超过明细金额';

CREATE TABLE tc_refund_item (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '退款金额归属明细内部主键',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '退款明细所属可信租户',
  refund_id BIGINT UNSIGNED NOT NULL COMMENT '退款申请聚合主键',
  order_id BIGINT UNSIGNED NOT NULL COMMENT '原订单主键，关联申请与组件额度',
  component_key VARCHAR(64) NOT NULL COMMENT '原冻结组件键，同一申请不得重复',
  component_type VARCHAR(16) NOT NULL COMMENT '商品运费或税金额归属',
  order_item_id BIGINT UNSIGNED NULL COMMENT '商品明细主键，非商品为空',
  quantity BIGINT UNSIGNED NOT NULL COMMENT '涉及商品数量，可为零表示金额调整，非退货入库数量',
  amount BIGINT UNSIGNED NOT NULL COMMENT '该组件本次退款最小币种单位金额',
  created_at DATETIME(3) NOT NULL COMMENT '归属明细冻结UTC时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_refund_item_component (tenant_id,refund_id,component_key),
  CONSTRAINT fk_refund_item_application FOREIGN KEY (tenant_id,refund_id,order_id) REFERENCES tc_refund(tenant_id,id,order_id),
  CONSTRAINT fk_refund_item_budget FOREIGN KEY (tenant_id,order_id,component_key) REFERENCES tc_refund_component_budget(tenant_id,order_id,component_key),
  CONSTRAINT chk_refund_item_amount CHECK (amount>0 AND amount<=9007199254740991),
  CONSTRAINT chk_refund_item_type CHECK (
    (component_type='GOODS' AND order_item_id IS NOT NULL AND component_key=CONCAT('GOODS:',order_item_id))
    OR (component_type IN ('FREIGHT','TAX') AND order_item_id IS NULL AND component_key=component_type AND quantity=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='退款申请不可变金额归属明细，不直接作为实物退货事实';
