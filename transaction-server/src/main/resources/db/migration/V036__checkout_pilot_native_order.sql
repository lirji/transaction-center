ALTER TABLE tc_quote_item
  DROP CHECK tc_quote_item_chk_2,
  ADD COLUMN discount_amount BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '报价行冻结优惠分值，固定价报价保持零',
  MODIFY COLUMN payable_amount BIGINT UNSIGNED NOT NULL COMMENT '本行原价扣除冻结优惠后的应付分值',
  ADD CONSTRAINT ck_quote_item_discount CHECK (
    discount_amount <= unit_price * quantity
    AND payable_amount = unit_price * quantity - discount_amount
    AND payable_amount <= 9007199254740991
  );

ALTER TABLE tc_order
  MODIFY COLUMN discount_amount BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '冻结订单优惠合计分值';

ALTER TABLE tc_order_item
  MODIFY COLUMN discount_amount BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '冻结订单明细优惠分值';

ALTER TABLE tc_checkout_pilot_quote_ref
  ADD COLUMN native_order_id BIGINT UNSIGNED NULL COMMENT '绑定的原生订单主键',
  ADD COLUMN order_request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原生订单创建请求的幂等摘要',
  ADD COLUMN order_bound_at DATETIME(3) NULL COMMENT '试点报价关联原生订单的UTC时间',
  ADD UNIQUE KEY uk_pilot_native_order (tenant_id, native_order_id),
  ADD CONSTRAINT fk_pilot_native_order FOREIGN KEY (tenant_id, native_order_id) REFERENCES tc_order (tenant_id, id),
  ADD CONSTRAINT ck_pilot_order_binding CHECK (
    (native_order_id IS NULL AND order_request_hash IS NULL AND order_bound_at IS NULL)
    OR (native_order_id IS NOT NULL AND order_request_hash IS NOT NULL AND order_bound_at IS NOT NULL
      AND price_proof_hash IS NOT NULL)
  );
