-- 已执行V001～V015保持不变；新增主动退款恢复及初始任务补漏。
ALTER TABLE tc_job_lease DROP CHECK chk_job_type,
  MODIFY COLUMN job_type VARCHAR(64) NOT NULL COMMENT '白名单任务类型，支付恢复回调订单解析及退款恢复回调',
  ADD CONSTRAINT chk_job_type CHECK (job_type IN ('PAYMENT_RECOVER','PAYMENT_CALLBACK','ORDER_RESOLVE','REFUND_CALLBACK','REFUND_RECOVER'));

INSERT INTO tc_job_lease(tenant_id,job_type,business_key,status,next_run_at,created_at,updated_at)
SELECT tenant_id,'REFUND_RECOVER',refund_no,'READY',
  CASE WHEN status='PENDING_APPROVAL' THEN approval_deadline ELSE UTC_TIMESTAMP(3) END,
  UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)
FROM tc_refund WHERE status IN ('PENDING_APPROVAL','READY','SUBMITTED')
ON DUPLICATE KEY UPDATE id=tc_job_lease.id;
