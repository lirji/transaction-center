ALTER TABLE tc_payment_callback
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '支付回调的运营接收号',
  ADD UNIQUE KEY uk_payment_callback_business_no (business_no);
ALTER TABLE tc_refund_callback
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '退款回调的运营接收号',
  ADD UNIQUE KEY uk_refund_callback_business_no (business_no);
ALTER TABLE tc_refund
  ADD COLUMN approval_business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '审批单可读编号，保留原审批引用',
  ADD UNIQUE KEY uk_refund_approval_business_no (approval_business_no);
