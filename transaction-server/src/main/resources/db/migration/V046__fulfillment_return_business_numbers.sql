ALTER TABLE tc_order_fulfillment
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '履约单的统一展示号',
  ADD UNIQUE KEY uk_fulfillment_business_no (business_no);

ALTER TABLE tc_return_request
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '退货申请的统一展示号',
  ADD UNIQUE KEY uk_return_request_business_no (business_no);

ALTER TABLE tc_return_receipt
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '退货验收的统一展示号，回库继续使用原验收号',
  ADD UNIQUE KEY uk_return_receipt_business_no (business_no);
