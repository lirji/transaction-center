ALTER TABLE tc_order
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '跨平台展示与精确查询的交易订单号',
  ADD COLUMN business_date DATE AS (DATE(created_at + INTERVAL 8 HOUR)) VIRTUAL COMMENT '订单创建时刻对应的北京时间业务日',
  ADD COLUMN no_tail CHAR(4) CHARACTER SET ascii COLLATE ascii_bin AS (RIGHT(business_no,4)) VIRTUAL COMMENT '可读订单号的四位尾号',
  ADD UNIQUE KEY uk_order_business_no (business_no),
  ADD KEY ix_order_tenant_day_tail (tenant_id,business_date,no_tail,id);

-- 历史记录通过可重跑的分批补录写入 business_no，原 order_no 与关联始终保持稳定。
