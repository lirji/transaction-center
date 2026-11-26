ALTER TABLE tc_quote
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '普通与试点原生报价的可读号，签名引用仍使用原报价键',
  ADD UNIQUE KEY uk_quote_business_no (business_no);
