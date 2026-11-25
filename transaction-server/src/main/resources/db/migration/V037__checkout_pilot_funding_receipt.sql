ALTER TABLE tc_checkout_pilot_quote_ref
  ADD COLUMN funding_application_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '资金服务首次预留申请编号',
  ADD COLUMN funding_receipt_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '首次接受的预算回执签名摘要',
  ADD COLUMN funding_valid_until DATETIME(3) NULL COMMENT '预算回执允许创建支付意图的UTC上限',
  ADD CONSTRAINT ck_checkout_funding_receipt CHECK (
    (funding_application_id IS NULL AND funding_receipt_hash IS NULL AND funding_valid_until IS NULL)
    OR (funding_application_id IS NOT NULL AND funding_receipt_hash IS NOT NULL
      AND funding_valid_until IS NOT NULL AND native_order_id IS NOT NULL)
  );
