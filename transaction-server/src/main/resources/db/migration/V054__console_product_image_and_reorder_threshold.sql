-- 控制台管理的商品展示图独立于成交快照，库存补货阈值不参与可售或占用计算。
ALTER TABLE tc_product
    ADD COLUMN image_url VARCHAR(1024) NULL COMMENT '运营配置的 HTTPS 商品展示图，NULL 表示未配置';

ALTER TABLE tc_inventory_balance
    ADD COLUMN reorder_threshold BIGINT NULL COMMENT '可用量补货阈值，NULL 表示未配置',
    ADD COLUMN reorder_revision BIGINT NOT NULL DEFAULT 0 COMMENT '阈值配置版本，与库存流水版本独立',
    ADD CONSTRAINT ck_inventory_reorder_threshold CHECK (reorder_threshold IS NULL OR reorder_threshold >= 0);
