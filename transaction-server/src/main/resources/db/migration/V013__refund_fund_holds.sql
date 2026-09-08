-- 收款与退款预占的订单归属由复合外键验证，不修改已执行历史迁移。
ALTER TABLE tc_payment_receipt ADD UNIQUE KEY uk_receipt_order_ref (tenant_id,id,order_id);

CREATE TABLE tc_payment_refund_hold (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '退款资金预占内部主键',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '可信租户，所有读写显式限定',
  reservation_no VARCHAR(64) NOT NULL COMMENT '本地永久预占编号，区分新插入与幂等重放',
  refund_no VARCHAR(64) NOT NULL COMMENT '退款应用稳定业务编号，同号不得重新占款',
  receipt_id BIGINT UNSIGNED NOT NULL COMMENT '退款来源真实收款主键',
  receipt_no VARCHAR(64) NOT NULL COMMENT '退款来源收款编号快照',
  order_id BIGINT UNSIGNED NOT NULL COMMENT '来源订单主键，不允许跨订单预占',
  amount BIGINT UNSIGNED NOT NULL COMMENT '本次退款最小币种单位金额',
  currency CHAR(3) NOT NULL COMMENT '原真实收款币种',
  channel_code VARCHAR(64) NOT NULL COMMENT '原收款渠道快照',
  channel_merchant_no VARCHAR(255) NOT NULL COMMENT '原渠道收款主体快照',
  state VARCHAR(16) NOT NULL DEFAULT 'HELD' COMMENT '已预占已派发已释放或已成功状态',
  provider_refund_no VARCHAR(255) NULL COMMENT '渠道退款交易号，成功后永久去重',
  submitted_at DATETIME(3) NULL COMMENT '首次派发许可UTC时间，不能清除',
  provider_succeeded_at DATETIME(3) NULL COMMENT '渠道成功UTC时间，仅作资金证据',
  confirmed_at DATETIME(3) NULL COMMENT '本地确认退款成功UTC时间',
  released_at DATETIME(3) NULL COMMENT '可靠失败或未派发拒绝释放UTC时间',
  evidence_ref VARCHAR(255) NULL COMMENT '成功或可靠失败证据引用，不存敏感原文',
  created_at DATETIME(3) NOT NULL COMMENT '本地创建UTC时间',
  updated_at DATETIME(3) NOT NULL COMMENT '状态最近更新UTC时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_refund_hold_no (tenant_id,refund_no),
  UNIQUE KEY uk_refund_reservation (tenant_id,reservation_no),
  UNIQUE KEY uk_refund_hold_receipt (tenant_id,receipt_id,refund_no),
  UNIQUE KEY uk_provider_refund (channel_code,channel_merchant_no,provider_refund_no),
  CONSTRAINT fk_hold_receipt_order FOREIGN KEY (tenant_id,receipt_id,order_id)
    REFERENCES tc_payment_receipt(tenant_id,id,order_id),
  CONSTRAINT chk_refund_hold_amount CHECK (amount>0 AND amount<=9007199254740991),
  CONSTRAINT chk_refund_hold_currency CHECK (currency REGEXP '^[A-Z]{3}$'),
  CONSTRAINT chk_refund_hold_state CHECK (
    (state='HELD' AND submitted_at IS NULL AND provider_refund_no IS NULL AND provider_succeeded_at IS NULL AND confirmed_at IS NULL AND released_at IS NULL AND evidence_ref IS NULL)
    OR (state='SUBMITTED' AND submitted_at IS NOT NULL AND provider_refund_no IS NULL AND provider_succeeded_at IS NULL AND confirmed_at IS NULL AND released_at IS NULL AND evidence_ref IS NULL)
    OR (state='RELEASED' AND provider_refund_no IS NULL AND provider_succeeded_at IS NULL AND confirmed_at IS NULL AND released_at IS NOT NULL AND evidence_ref IS NOT NULL)
    OR (state='SUCCEEDED' AND provider_refund_no IS NOT NULL AND provider_succeeded_at IS NOT NULL AND confirmed_at IS NOT NULL AND released_at IS NULL AND evidence_ref IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='支付域永久退款资金预占及可信成功事实';

-- 本机及隔离MySQL已核对原约束名；历史收款行保持原值，不能用退款覆盖收款流水。
ALTER TABLE tc_money_ledger
  COMMENT='支付域真实收款与退款资金账本，原始流水永久保留',
  MODIFY COLUMN entry_type VARCHAR(16) NOT NULL COMMENT '资金方向：RECEIPT收款，REFUND原路退款',
  ADD COLUMN refund_no VARCHAR(64) NULL COMMENT '退款资金流水的稳定业务编号，收款流水为空',
  ADD CONSTRAINT fk_ledger_refund_hold FOREIGN KEY (tenant_id,receipt_id,refund_no)
    REFERENCES tc_payment_refund_hold(tenant_id,receipt_id,refund_no),
  DROP CHECK tc_money_ledger_chk_1,
  ADD CONSTRAINT chk_money_ledger_direction CHECK (
    (entry_type='RECEIPT' AND refund_no IS NULL) OR (entry_type='REFUND' AND refund_no IS NOT NULL));
