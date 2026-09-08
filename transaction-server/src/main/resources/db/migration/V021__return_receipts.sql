-- 原明细全量履约后累计实收预算与原预占累计可售回库预算分别记账。
ALTER TABLE tc_order_item
 ADD COLUMN returned_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '累计验收实收数量，含不可售品，不超过实际全量交付的冻结明细数量',
 ADD CONSTRAINT chk_item_returned CHECK (returned_quantity>=0 AND returned_quantity<=quantity);
ALTER TABLE tc_inventory_reservation
 ADD COLUMN restocked_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '原已确认预占累计可售回库数量，不改变CONFIRMED历史终态',
 ADD CONSTRAINT chk_reservation_restocked CHECK (restocked_quantity>=0 AND restocked_quantity<=quantity AND (restocked_quantity=0 OR status='CONFIRMED'));
CREATE TABLE tc_return_receipt (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '验收事实内部主键',
 tenant_id BIGINT UNSIGNED NOT NULL COMMENT '可信仓储来源所属租户',
 return_no VARCHAR(48) NOT NULL COMMENT '永久验收编号，同时作为可售回库父操作编号',
 order_id BIGINT UNSIGNED NOT NULL COMMENT '实际履约的原订单',
 order_item_id BIGINT UNSIGNED NOT NULL COMMENT '冻结的原实物订单明细',
 source_actor_key CHAR(64) NOT NULL COMMENT '可信验收来源身份摘要',
 source_request_id VARCHAR(128) NOT NULL COMMENT '来源永久事件号，不依赖短期命令',
 warehouse_id BIGINT UNSIGNED NOT NULL COMMENT '实际接收仓库，可不同于原出库仓',
 received_quantity BIGINT NOT NULL COMMENT '本次实际验收收到数量，包含不合格品',
 restock_quantity BIGINT NOT NULL COMMENT '本次可售回库数量，零表示全部不合格，不可后改',
 inspection_ref VARCHAR(255) NOT NULL COMMENT '验收证据引用，不存储个人明文',
 request_hash CHAR(64) NOT NULL COMMENT '原订单项、接收仓、数量与验收来源内容摘要',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '验收事实本地登记UTC时间',
 PRIMARY KEY(id),
 UNIQUE KEY uk_return_number(tenant_id,return_no),
 UNIQUE KEY uk_return_source(tenant_id,source_actor_key,source_request_id),
 KEY idx_return_item(tenant_id,order_id,order_item_id,id),
 CONSTRAINT fk_return_item FOREIGN KEY(tenant_id,order_id,order_item_id) REFERENCES tc_order_item(tenant_id,order_id,id),
 CONSTRAINT fk_return_fulfillment FOREIGN KEY(tenant_id,order_id) REFERENCES tc_order_fulfillment(tenant_id,order_id),
 CONSTRAINT fk_return_warehouse FOREIGN KEY(tenant_id,warehouse_id) REFERENCES tc_warehouse(tenant_id,id),
 CONSTRAINT chk_return_quantities CHECK(received_quantity>0 AND restock_quantity>=0 AND restock_quantity<=received_quantity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='独立已完成检验的退货实收事实，不由退款结果自动产生';
CREATE TABLE tc_inventory_return_operation (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '库存回库操作内部主键',
 tenant_id BIGINT UNSIGNED NOT NULL COMMENT '原预占与接收仓所属租户',
 operation_no VARCHAR(48) NOT NULL COMMENT '永久父回库操作编号，派生唯一OUT及IN库存流水键',
 reservation_id BIGINT UNSIGNED NOT NULL COMMENT '原CONFIRMED库存预占，独立维护累计回库上限',
 warehouse_id BIGINT UNSIGNED NOT NULL COMMENT '接收可售商品的目标仓库',
 quantity BIGINT NOT NULL COMMENT '本次可售回库数量，不包括不合格实收',
 request_hash CHAR(64) NOT NULL COMMENT '原预占SKU数量与目标仓库绑定摘要',
 claim_token CHAR(36) NOT NULL COMMENT '区分本事务首次插入与永久重放的内部标记',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '库存回库本地登记UTC时间',
 PRIMARY KEY(id),
 UNIQUE KEY uk_inventory_return_operation(tenant_id,operation_no),
 KEY idx_inventory_return_reservation(tenant_id,reservation_id,id),
 CONSTRAINT fk_inventory_return_reservation FOREIGN KEY(tenant_id,reservation_id) REFERENCES tc_inventory_reservation(tenant_id,id),
 CONSTRAINT fk_inventory_return_warehouse FOREIGN KEY(tenant_id,warehouse_id) REFERENCES tc_warehouse(tenant_id,id),
 CONSTRAINT chk_inventory_return_quantity CHECK(quantity>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='永久唯一可售回库操作，原仓与接收仓余额流水同事务守恒';
