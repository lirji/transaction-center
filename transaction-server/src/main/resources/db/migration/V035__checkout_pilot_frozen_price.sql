ALTER TABLE tc_checkout_pilot_quote_ref
  ADD COLUMN price_proof_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '冻结时首次接受的营销签名摘要',
  ADD COLUMN price_proof MEDIUMTEXT CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当次结算的原始签名价格凭证',
  ADD COLUMN gross_minor BIGINT UNSIGNED NULL COMMENT '冻结的商品原价分值',
  ADD COLUMN discount_minor BIGINT UNSIGNED NULL COMMENT '冻结的试点优惠分值',
  ADD COLUMN payable_minor BIGINT UNSIGNED NULL COMMENT '冻结的交易应付分值',
  ADD COLUMN price_expires_at DATETIME(3) NULL COMMENT '冻结价格可用于创建订单的截止时间',
  ADD CONSTRAINT ck_pilot_frozen_price CHECK (
    (price_proof_hash IS NULL AND price_proof IS NULL AND gross_minor IS NULL AND discount_minor IS NULL
      AND payable_minor IS NULL AND price_expires_at IS NULL)
    OR
    (price_proof_hash IS NOT NULL AND price_proof IS NOT NULL AND gross_minor IS NOT NULL
      AND discount_minor IS NOT NULL AND payable_minor IS NOT NULL AND price_expires_at IS NOT NULL
      AND discount_minor > 0 AND payable_minor > 0 AND gross_minor <= 9007199254740991
      AND gross_minor = discount_minor + payable_minor AND price_expires_at <= expires_at));
