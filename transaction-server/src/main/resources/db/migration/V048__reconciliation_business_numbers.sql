ALTER TABLE tc_reconciliation_case
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '差异管理编号，原报告修订链继续引用差异 UUID',
  ADD UNIQUE KEY uk_reconciliation_case_business_no (business_no);

ALTER TABLE tc_reconciliation_operation
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '操作管理编号，任务租约和已有查询继续引用操作 UUID',
  ADD UNIQUE KEY uk_reconciliation_operation_business_no (business_no);
