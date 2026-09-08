-- V010仅扩展任务协议；保持V001～V009校验和及已有终态不变。
ALTER TABLE tc_job_lease
  ADD COLUMN wake_generation BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计业务唤醒代数，运行中也记录新唤醒',
  ADD COLUMN claimed_wake_generation BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前领取时已看到的唤醒代数，退避不能吞掉后续唤醒',
  ADD CONSTRAINT chk_job_wake_generation CHECK (claimed_wake_generation<=wake_generation),
  MODIFY COLUMN job_type VARCHAR(64) NOT NULL COMMENT '白名单任务类型，支付恢复回调处理或订单关闭解析',
  MODIFY COLUMN business_key VARCHAR(128) NOT NULL COMMENT '稳定业务引用，按任务类型为渠道请求号回调号或订单号',
  DROP CHECK chk_job_type,
  ADD CONSTRAINT chk_job_type CHECK (job_type IN ('PAYMENT_RECOVER','PAYMENT_CALLBACK','ORDER_RESOLVE'));

-- 初始补漏覆盖从未创建支付尝试的订单；滚动升级仍须旧写入退出后从零分页收尾。
INSERT INTO tc_job_lease(tenant_id,job_type,business_key,status,next_run_at,created_at,updated_at)
SELECT tenant_id,'ORDER_RESOLVE',order_no,'READY',
  CASE WHEN status='CLOSING' THEN UTC_TIMESTAMP(3) ELSE expires_at END,
  UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)
FROM tc_order WHERE payable_amount>0 AND status IN ('PENDING_PAYMENT','CLOSING');
