CREATE TABLE tc_command (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键，对外以字符串表示',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属租户，所有业务读写必须限定',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间，毫秒精度',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新UTC时间',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观并发版本号',
  actor_key VARCHAR(64) NOT NULL COMMENT '操作主体摘要，来自可信身份',
  operation VARCHAR(64) NOT NULL COMMENT '业务命令类型',
  request_key VARCHAR(128) NOT NULL COMMENT '客户端或领域稳定幂等键',
  request_hash CHAR(64) NOT NULL COMMENT '规范化请求摘要，相同键不同内容拒绝',
  result_ref VARCHAR(64) NULL COMMENT '事务完成后的业务结果引用',
  UNIQUE KEY uk_command (tenant_id, actor_key, operation, request_key),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tc_command_tenant_id (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='业务幂等命令及结果审计表';

CREATE TABLE tc_outbox (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键，对外以字符串表示',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属租户，所有业务读写必须限定',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间，毫秒精度',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新UTC时间',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观并发版本号',
  event_id CHAR(36) NOT NULL COMMENT '全局事件编号，消费者去重依据',
  event_type VARCHAR(64) NOT NULL COMMENT '事件类型及主版本',
  aggregate_id VARCHAR(64) NOT NULL COMMENT '聚合业务主键',
  payload JSON NOT NULL COMMENT '不含个人敏感明文的事件载荷',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '投递状态，首批只持久化待发布事实',
  UNIQUE KEY uk_event (event_id),
  KEY ix_outbox (status, id),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tc_outbox_tenant_id (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='本地事务待发布事件表';

CREATE TABLE tc_member (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键，对外以字符串表示',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属租户，所有业务读写必须限定',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间，毫秒精度',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新UTC时间',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观并发版本号',
  identity_hash CHAR(64) NOT NULL COMMENT '租户签发方主体规范编码摘要',
  issuer VARCHAR(255) NOT NULL COMMENT '可信身份签发方',
  subject VARCHAR(255) NOT NULL COMMENT '可信登录主体，不接受前端任意指定',
  display_name VARCHAR(100) NOT NULL COMMENT '会员展示名称',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '会员业务状态',
  registered_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '业务注册UTC时间',
  UNIQUE KEY uk_member_identity (tenant_id, identity_hash),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tc_member_tenant_id (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='业务会员主表';

CREATE TABLE tc_member_trade_fact (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键，对外以字符串表示',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属租户，所有业务读写必须限定',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间，毫秒精度',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新UTC时间',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观并发版本号',
  member_id BIGINT UNSIGNED NOT NULL COMMENT '业务会员主键',
  ever_paid BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否曾经足额支付，退款不能清除',
  first_paid_order_id BIGINT UNSIGNED NULL COMMENT '首次本地确认支付的订单主键',
  first_confirmed_at DATETIME(3) NULL COMMENT '首次本地确认支付UTC时间',
  UNIQUE KEY uk_member_fact (tenant_id, member_id),
  FOREIGN KEY (tenant_id, member_id) REFERENCES tc_member (tenant_id, id),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tc_member_trade_fact_tenant_id (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='会员不可逆消费历史事实表';

CREATE TABLE tc_product (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键，对外以字符串表示',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属租户，所有业务读写必须限定',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间，毫秒精度',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新UTC时间',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观并发版本号',
  product_no VARCHAR(64) NOT NULL COMMENT '租户内商品业务编号',
  name VARCHAR(200) NOT NULL COMMENT '商品草稿名称',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '草稿上架或下架状态',
  published_revision BIGINT UNSIGNED NULL COMMENT '最近一次发布版本',
  UNIQUE KEY uk_product_no (tenant_id, product_no),
  KEY ix_product_list (tenant_id, status, id),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tc_product_tenant_id (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='商品主档与草稿表';

CREATE TABLE tc_sku (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键，对外以字符串表示',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属租户，所有业务读写必须限定',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间，毫秒精度',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新UTC时间',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观并发版本号',
  product_id BIGINT UNSIGNED NOT NULL COMMENT '所属商品主键',
  sku_no VARCHAR(64) NOT NULL COMMENT '租户内SKU业务编号',
  unit_price BIGINT UNSIGNED NOT NULL COMMENT '币种最小单位售价，禁止浮点',
  currency CHAR(3) NOT NULL COMMENT '大写三字符币种',
  UNIQUE KEY uk_sku_no (tenant_id, sku_no),
  KEY ix_sku_product (tenant_id, product_id, id),
  FOREIGN KEY (tenant_id, product_id) REFERENCES tc_product (tenant_id, id),
  CHECK (unit_price <= 9007199254740991),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tc_sku_tenant_id (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='商品SKU草稿表';

CREATE TABLE tc_sku_revision (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键，对外以字符串表示',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属租户，所有业务读写必须限定',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间，毫秒精度',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新UTC时间',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观并发版本号',
  sku_id BIGINT UNSIGNED NOT NULL COMMENT 'SKU主键',
  revision BIGINT UNSIGNED NOT NULL COMMENT '商品发布版本',
  title VARCHAR(200) NOT NULL COMMENT '发布时商品名称快照',
  unit_price BIGINT UNSIGNED NOT NULL COMMENT '发布时最小币种单位价格',
  currency CHAR(3) NOT NULL COMMENT '发布币种快照',
  UNIQUE KEY uk_sku_revision (tenant_id, sku_id, revision),
  FOREIGN KEY (tenant_id, sku_id) REFERENCES tc_sku (tenant_id, id),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tc_sku_revision_tenant_id (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='不可变SKU发布快照表';

CREATE TABLE tc_warehouse (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键，对外以字符串表示',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属租户，所有业务读写必须限定',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间，毫秒精度',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新UTC时间',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观并发版本号',
  warehouse_no VARCHAR(64) NOT NULL COMMENT '租户内仓库编号',
  name VARCHAR(200) NOT NULL COMMENT '仓库名称',
  UNIQUE KEY uk_warehouse (tenant_id, warehouse_no),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tc_warehouse_tenant_id (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='仓库主档表';

CREATE TABLE tc_inventory_balance (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键，对外以字符串表示',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属租户，所有业务读写必须限定',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间，毫秒精度',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新UTC时间',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观并发版本号',
  warehouse_id BIGINT UNSIGNED NOT NULL COMMENT '仓库主键',
  sku_id BIGINT UNSIGNED NOT NULL COMMENT 'SKU主键',
  stock_total BIGINT NOT NULL DEFAULT 0 COMMENT '盘点调整后的库存总量',
  available BIGINT NOT NULL DEFAULT 0 COMMENT '可预占数量',
  reserved BIGINT NOT NULL DEFAULT 0 COMMENT '未决订单预占数量',
  sold BIGINT NOT NULL DEFAULT 0 COMMENT '已售且尚未退回可售库存数量',
  UNIQUE KEY uk_balance (tenant_id, warehouse_id, sku_id),
  FOREIGN KEY (tenant_id, warehouse_id) REFERENCES tc_warehouse (tenant_id, id),
  FOREIGN KEY (tenant_id, sku_id) REFERENCES tc_sku (tenant_id, id),
  CHECK (available >= 0 AND reserved >= 0 AND sold >= 0 AND stock_total = available + reserved + sold),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tc_inventory_balance_tenant_id (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='SKU仓库库存余额表';

CREATE TABLE tc_inventory_reservation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键，对外以字符串表示',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属租户，所有业务读写必须限定',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间，毫秒精度',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新UTC时间',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观并发版本号',
  reservation_key VARCHAR(128) NOT NULL COMMENT '订单模块提供的稳定预占键，未来使用订单明细编号',
  warehouse_id BIGINT UNSIGNED NOT NULL COMMENT '预占仓库主键',
  sku_id BIGINT UNSIGNED NOT NULL COMMENT '预占SKU主键',
  quantity BIGINT NOT NULL COMMENT '正数预占数量',
  status VARCHAR(16) NOT NULL DEFAULT 'RESERVED' COMMENT '预占确认或释放终态',
  UNIQUE KEY uk_reservation (tenant_id, reservation_key),
  KEY ix_reservation_balance (tenant_id, warehouse_id, sku_id),
  FOREIGN KEY (tenant_id, warehouse_id, sku_id) REFERENCES tc_inventory_balance (tenant_id, warehouse_id, sku_id),
  CHECK (quantity > 0),
  CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED')),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tc_inventory_reservation_tenant_id (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='订单库存预占明细及终态表';

CREATE TABLE tc_inventory_ledger (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键，对外以字符串表示',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属租户，所有业务读写必须限定',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间，毫秒精度',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近更新UTC时间',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观并发版本号',
  operation_ref VARCHAR(64) NOT NULL COMMENT '业务幂等操作编号',
  warehouse_id BIGINT UNSIGNED NOT NULL COMMENT '仓库主键',
  sku_id BIGINT UNSIGNED NOT NULL COMMENT 'SKU主键',
  operation_type VARCHAR(16) NOT NULL COMMENT '调整预占确认或释放',
  available_delta BIGINT NOT NULL COMMENT '可售库存有符号变化量',
  reserved_delta BIGINT NOT NULL COMMENT '预占库存有符号变化量',
  sold_delta BIGINT NOT NULL COMMENT '已售库存有符号变化量',
  total_delta BIGINT NOT NULL COMMENT '库存总量有符号变化量',
  reason VARCHAR(255) NOT NULL COMMENT '业务原因或关联预占编号',
  actor_key VARCHAR(64) NOT NULL COMMENT '操作身份摘要，便于审计',
  UNIQUE KEY uk_stock_operation (tenant_id, operation_ref),
  KEY ix_ledger_balance (tenant_id, warehouse_id, sku_id, id),
  FOREIGN KEY (tenant_id, warehouse_id, sku_id) REFERENCES tc_inventory_balance (tenant_id, warehouse_id, sku_id),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tc_inventory_ledger_tenant_id (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='不可变库存操作流水表';

