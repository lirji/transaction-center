-- 固定状态全局后台观测使用覆盖索引，避免租户前缀索引导致全表扫描。
ALTER TABLE tc_payment_attempt ADD INDEX ix_attempt_observation (status,submitted_at,id) COMMENT '资金积压固定状态最老时间有界观测';
ALTER TABLE tc_payment_refund_hold ADD INDEX ix_hold_observation (state,created_at,id) COMMENT '资金积压固定状态最老时间有界观测';
ALTER TABLE tc_payment_callback ADD INDEX ix_payment_callback_observation (status,received_at,id) COMMENT '资金积压固定状态最老时间有界观测';
ALTER TABLE tc_refund_callback ADD INDEX ix_refund_callback_observation (status,received_at,id) COMMENT '资金积压固定状态最老时间有界观测';
ALTER TABLE tc_reconciliation_case ADD INDEX ix_recon_case_observation (status,created_at,id) COMMENT '资金积压固定状态最老时间有界观测';
