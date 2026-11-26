ALTER TABLE tc_payment_receipt
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '实收展示号，原实收号仍用于回执和资金关联',
  ADD UNIQUE KEY uk_payment_receipt_business_no (business_no);

ALTER TABLE tc_money_ledger
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '实收与退款共用的资金流水展示号',
  ADD UNIQUE KEY uk_money_ledger_business_no (business_no);
