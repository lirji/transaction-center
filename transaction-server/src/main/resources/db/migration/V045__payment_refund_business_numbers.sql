ALTER TABLE tc_payment_intent
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '支付意图展示号，渠道尝试继续使用原商户请求号',
  ADD UNIQUE KEY uk_payment_business_no (business_no);

ALTER TABLE tc_refund
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '退款查询号，原退款号及渠道请求号继续用于在途核销',
  ADD UNIQUE KEY uk_refund_business_no (business_no);
