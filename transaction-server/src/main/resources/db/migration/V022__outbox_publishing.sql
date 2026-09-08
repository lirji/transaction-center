-- 新版本只追加发布元数据，不改写历史事件载荷；历史入队由有界补漏服务处理。
ALTER TABLE tc_job_lease DROP CHECK chk_job_type,
  MODIFY COLUMN job_type VARCHAR(64) NOT NULL COMMENT '白名单任务类型，包含支付退款订单处理及Outbox发布',
  ADD CONSTRAINT chk_job_type CHECK (job_type IN ('PAYMENT_RECOVER','PAYMENT_CALLBACK','ORDER_RESOLVE','REFUND_CALLBACK','REFUND_RECOVER','REFUND_COMPENSATE','OUTBOX_PUBLISH'));
ALTER TABLE tc_outbox
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '发布状态PENDING待发送SENT已确认REVIEW人工隔离',
  ADD COLUMN published_at DATETIME(3) NULL COMMENT '收到Broker确认后本地提交成功的UTC时间',
  ADD COLUMN published_topic VARCHAR(249) NULL COMMENT '已确认消息的固定环境主题名',
  ADD COLUMN published_partition INT NULL COMMENT 'Broker确认的非负分区编号',
  ADD COLUMN published_offset BIGINT NULL COMMENT 'Broker确认的非负分区偏移',
  ADD COLUMN first_failure_at DATETIME(3) NULL COMMENT '首次发布失败UTC时间，成功后仍保留',
  ADD COLUMN last_failure_at DATETIME(3) NULL COMMENT '最近发布失败UTC时间，成功后仍保留',
  ADD COLUMN failure_count BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已持久登记的失败次数，不含进程崩溃次数',
  ADD COLUMN last_publish_error VARCHAR(64) NULL COMMENT '最近发布失败脱敏分类码，不存异常报文',
  ADD CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING','SENT','REVIEW')),
  ADD CONSTRAINT chk_outbox_ack CHECK ((status='SENT' AND published_at IS NOT NULL AND published_topic IS NOT NULL AND published_partition>=0 AND published_offset>=0 AND published_partition IS NOT NULL AND published_offset IS NOT NULL) OR (status<>'SENT' AND published_at IS NULL AND published_topic IS NULL AND published_partition IS NULL AND published_offset IS NULL));
