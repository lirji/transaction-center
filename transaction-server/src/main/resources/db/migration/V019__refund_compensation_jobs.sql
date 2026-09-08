-- 新资金事实与补偿任务原子提交；迁移为已有异常收款建立初始任务，已完成任务不重启。
ALTER TABLE tc_job_lease DROP CHECK chk_job_type,
  MODIFY COLUMN job_type VARCHAR(64) NOT NULL COMMENT '白名单任务类型，支付恢复回调订单解析退款恢复回调及异常收款补偿',
  ADD CONSTRAINT chk_job_type CHECK (job_type IN ('PAYMENT_RECOVER','PAYMENT_CALLBACK','ORDER_RESOLVE','REFUND_CALLBACK','REFUND_RECOVER','REFUND_COMPENSATE'));

INSERT INTO tc_job_lease(tenant_id,job_type,business_key,status,next_run_at,created_at,updated_at)
SELECT tenant_id,'REFUND_COMPENSATE',receipt_no,'READY',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)
FROM tc_payment_receipt WHERE disposition IN ('EXCESS','AFTER_TERMINAL','AFTER_CLOSE','AMOUNT_MISMATCH')
ON DUPLICATE KEY UPDATE id=tc_job_lease.id;
