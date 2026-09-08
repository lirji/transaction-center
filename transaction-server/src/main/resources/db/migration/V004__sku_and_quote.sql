-- 规格与商品类型同时进入不可变发布快照，旧数据默认保持实物商品及空规格。
ALTER TABLE tc_sku
  ADD COLUMN specification JSON NOT NULL DEFAULT (JSON_OBJECT()) COMMENT 'SKU规格键值，不超过十六个属性',
  ADD COLUMN stock_type VARCHAR(16) NOT NULL DEFAULT 'PHYSICAL' COMMENT 'PHYSICAL实物需库存，SERVICE服务不占库存',
  ADD CONSTRAINT ck_sku_stock_type CHECK (stock_type IN ('PHYSICAL','SERVICE'));
ALTER TABLE tc_sku_revision
  ADD COLUMN specification JSON NOT NULL DEFAULT (JSON_OBJECT()) COMMENT '发布时SKU规格快照，不随草稿修改',
  ADD COLUMN stock_type VARCHAR(16) NOT NULL DEFAULT 'PHYSICAL' COMMENT '发布时库存类型快照',
  ADD CONSTRAINT ck_revision_stock_type CHECK (stock_type IN ('PHYSICAL','SERVICE'));

CREATE TABLE tc_quote (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '报价内部主键，对外使用字符串',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属租户，报价不能跨租户使用',
  member_id BIGINT UNSIGNED NOT NULL COMMENT '报价所属会员，不能转让给其他会员',
  payable_amount BIGINT UNSIGNED NOT NULL COMMENT '币种最小单位的冻结应付总额',
  currency CHAR(3) NOT NULL COMMENT '报价单一币种',
  pricing_policy VARCHAR(64) NOT NULL COMMENT '定价策略版本，首期固定价',
  expires_at DATETIME(3) NOT NULL COMMENT '报价UTC失效时刻，等于该时刻即失效',
  created_at DATETIME(3) NOT NULL COMMENT '报价创建UTC时间，取服务端可信时钟',
  updated_at DATETIME(3) NOT NULL COMMENT '报价更新时间，快照不允许覆盖',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '报价记录版本，首期快照保持零',
  PRIMARY KEY (id),
  UNIQUE KEY uk_quote_tenant_id (tenant_id,id),
  KEY ix_quote_member (tenant_id,member_id,id),
  KEY ix_quote_expiry (expires_at,id),
  FOREIGN KEY (tenant_id,member_id) REFERENCES tc_member (tenant_id,id),
  CHECK (payable_amount <= 9007199254740991)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='服务端不可变报价主表，过期重试不会续期';

CREATE TABLE tc_quote_item (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '报价明细内部主键',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属租户，必须与报价一致',
  quote_id BIGINT UNSIGNED NOT NULL COMMENT '报价主表主键',
  sku_id BIGINT UNSIGNED NOT NULL COMMENT '商品SKU主键',
  sku_revision BIGINT UNSIGNED NOT NULL COMMENT '被冻结的SKU发布版本',
  title VARCHAR(200) NOT NULL COMMENT '商品名称快照',
  specification JSON NOT NULL COMMENT '规格键值快照',
  stock_type VARCHAR(16) NOT NULL COMMENT '库存类型快照，决定订单是否预占实物库存',
  quantity BIGINT UNSIGNED NOT NULL COMMENT '购买数量，不接受负数或零',
  unit_price BIGINT UNSIGNED NOT NULL COMMENT '币种最小单位单价快照',
  payable_amount BIGINT UNSIGNED NOT NULL COMMENT '本明细应付金额，等于单价乘数量',
  created_at DATETIME(3) NOT NULL COMMENT '创建UTC时间',
  updated_at DATETIME(3) NOT NULL COMMENT '最近更新UTC时间，快照不覆盖',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '记录版本，首期快照保持零',
  PRIMARY KEY (id),
  UNIQUE KEY uk_quote_item_tenant_id (tenant_id,id),
  UNIQUE KEY uk_quote_item_sku (tenant_id,quote_id,sku_id),
  FOREIGN KEY (tenant_id,quote_id) REFERENCES tc_quote (tenant_id,id),
  FOREIGN KEY (tenant_id,sku_id,sku_revision) REFERENCES tc_sku_revision (tenant_id,sku_id,revision),
  CHECK (quantity > 0 AND quantity <= 1000000000),
  CHECK (payable_amount = unit_price * quantity AND payable_amount <= 9007199254740991),
  CHECK (stock_type IN ('PHYSICAL','SERVICE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='服务端报价明细不可变快照表';
