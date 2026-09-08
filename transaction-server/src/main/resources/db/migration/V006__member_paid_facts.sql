-- 先增加兼容计数字段；现有消费事实尚未写入支付，此约束也会拒绝无法解释的旧数据。
ALTER TABLE tc_member_trade_fact
  ADD COLUMN paid_order_count BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '正常足额支付订单累计数，退款不递减',
  ADD CONSTRAINT ck_member_paid_summary CHECK (
    (ever_paid=0 AND first_paid_order_id IS NULL AND first_confirmed_at IS NULL AND paid_order_count=0)
    OR (ever_paid=1 AND first_paid_order_id IS NOT NULL AND first_confirmed_at IS NOT NULL AND paid_order_count>0)
  );

-- 复合外键额外绑定订单所属会员，防止同租户误把他人订单计入消费历史。
ALTER TABLE tc_order ADD UNIQUE KEY uk_order_member_reference (tenant_id,id,member_id);

CREATE TABLE tc_member_paid_order (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部消费认定主键，不作为授权凭据',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属租户，消费事实禁止跨租户读写',
  created_at DATETIME(3) NOT NULL COMMENT '事实创建UTC时间，毫秒精度',
  updated_at DATETIME(3) NOT NULL COMMENT '事实写入UTC时间，正常业务不修改历史',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '不可变记录版本，当前固定初始版本',
  member_id BIGINT UNSIGNED NOT NULL COMMENT '订单所属业务会员主键',
  order_id BIGINT UNSIGNED NOT NULL COMMENT '已由可信收款编排确认的正常足额支付订单主键',
  paid_amount BIGINT UNSIGNED NOT NULL COMMENT '已确认正常订单实收最小币种单位，不包含多付',
  currency CHAR(3) NOT NULL COMMENT '已确认正常订单币种，三个大写字母',
  confirmed_at DATETIME(3) NOT NULL COMMENT '取得会员事实锁后本地确认UTC时间，不采用渠道时间排序',
  is_first BOOLEAN NOT NULL COMMENT '是否本会员首次本地确认的有效足额支付订单',
  first_slot TINYINT GENERATED ALWAYS AS (CASE WHEN is_first=1 THEN 1 ELSE NULL END) STORED COMMENT '首单唯一约束槽位，非首单为空允许多笔',
  first_order_policy VARCHAR(64) NOT NULL COMMENT '不可变首单认定策略版本',
  fact_version BIGINT UNSIGNED NOT NULL COMMENT '本次正常支付使会员消费事实达到的版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_member_paid_order_tenant_id (tenant_id,id),
  UNIQUE KEY uk_member_paid_order (tenant_id,order_id),
  UNIQUE KEY uk_member_first_slot (tenant_id,member_id,first_slot),
  UNIQUE KEY uk_member_paid_version (tenant_id,member_id,fact_version),
  FOREIGN KEY (tenant_id,member_id) REFERENCES tc_member_trade_fact(tenant_id,member_id),
  FOREIGN KEY (tenant_id,order_id,member_id) REFERENCES tc_order(tenant_id,id,member_id),
  CHECK (paid_amount>0 AND paid_amount<=9007199254740991),
  CHECK (is_first IN (0,1)),
  CHECK (first_order_policy='FIRST_CONFIRMED_PAID_ORDER_V1'),
  CHECK (fact_version>0),
  CHECK (currency REGEXP '^[A-Z]{3}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='会员订单级不可逆消费认定及永久去重表';
