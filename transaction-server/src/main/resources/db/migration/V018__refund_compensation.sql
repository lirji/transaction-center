-- 异常资金不属于正常消费；永久关联独立于申请者及短期HTTP幂等记录。
ALTER TABLE tc_refund
  MODIFY receipt_id BIGINT UNSIGNED NOT NULL COMMENT '原真实实收主键，正常退款或异常补偿均绑定原收款',
  MODIFY receipt_no VARCHAR(64) NOT NULL COMMENT '原真实实收编号快照，不允许改换资金来源',
  ADD compensation_receipt_id BIGINT UNSIGNED NULL COMMENT '异常补偿永久收款关联，非空时同租户只允许一个补偿申请',
  ADD UNIQUE KEY uk_refund_compensation (tenant_id,compensation_receipt_id),
  ADD CONSTRAINT chk_refund_compensation CHECK (compensation_receipt_id IS NULL OR (compensation_receipt_id=receipt_id AND approval_ref IS NOT NULL));

ALTER TABLE tc_refund_component_budget
  MODIFY component_key VARCHAR(64) NOT NULL COMMENT '冻结组件键，商品GOODS加明细主键，异常EXCESS加收款主键',
  MODIFY component_type VARCHAR(16) NOT NULL COMMENT '退款归属GOODS商品FREIGHT运费TAX税EXCESS异常收款',
  DROP CHECK chk_refund_budget_type,
  ADD CONSTRAINT chk_refund_budget_type CHECK (
    (component_type='GOODS' AND order_item_id IS NOT NULL AND component_key=CONCAT('GOODS:',order_item_id))
    OR (component_type IN ('FREIGHT','TAX') AND order_item_id IS NULL AND component_key=component_type)
    OR (component_type='EXCESS' AND order_item_id IS NULL AND component_key REGEXP '^EXCESS:[1-9][0-9]{0,19}$'));

ALTER TABLE tc_refund_item
  MODIFY component_type VARCHAR(16) NOT NULL COMMENT '商品运费税或异常实收补偿金额归属',
  DROP CHECK chk_refund_item_type,
  ADD CONSTRAINT chk_refund_item_type CHECK (
    (component_type='GOODS' AND order_item_id IS NOT NULL AND component_key=CONCAT('GOODS:',order_item_id))
    OR (component_type IN ('FREIGHT','TAX') AND order_item_id IS NULL AND component_key=component_type AND quantity=0)
    OR (component_type='EXCESS' AND order_item_id IS NULL AND quantity=0 AND component_key REGEXP '^EXCESS:[1-9][0-9]{0,19}$'));
