CREATE TABLE tc_job_lease (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部任务主键，对外引用使用字符串',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '任务所属可信租户，领取和状态修改均须限定',
  job_type VARCHAR(64) NOT NULL COMMENT '白名单任务类型，当前为支付恢复',
  business_key VARCHAR(128) NOT NULL COMMENT '稳定业务引用，支付恢复使用原渠道请求号',
  status VARCHAR(16) NOT NULL DEFAULT 'READY' COMMENT '等待运行完成或人工核对状态',
  runnable TINYINT GENERATED ALWAYS AS (CASE WHEN status IN ('READY','RUNNING') THEN 1 ELSE 0 END) STORED COMMENT '可调度状态索引槽位，终态不参与到期扫描',
  owner VARCHAR(128) NULL COMMENT '当前工作进程实例标识，非运行态为空',
  lease_until DATETIME(3) NULL COMMENT '主库UTC租约失效时刻，过期不允许续活',
  fencing_token BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '每次领取或接管递增的令牌，旧令牌不能提交结果',
  attempts BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计领取次数，进程崩溃后的接管也计入',
  next_run_at DATETIME(3) NOT NULL COMMENT '下次可领取UTC时间，运行中等于租约到期时刻',
  last_error VARCHAR(64) NULL COMMENT '最近脱敏错误分类码，不存原文堆栈或密钥',
  created_at DATETIME(3) NOT NULL COMMENT '主库UTC创建时间',
  updated_at DATETIME(3) NOT NULL COMMENT '主库UTC最近状态修改时间',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '每次任务状态或租约修改递增的并发版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_job_business (tenant_id,job_type,business_key),
  KEY idx_job_due (tenant_id,job_type,runnable,next_run_at,id),
  CHECK (job_type IN ('PAYMENT_RECOVER')),
  CHECK (status IN ('READY','RUNNING','DONE','REVIEW')),
  CHECK ((status='RUNNING' AND owner IS NOT NULL AND lease_until IS NOT NULL
          AND next_run_at=lease_until AND fencing_token>0 AND attempts>0)
      OR (status<>'RUNNING' AND owner IS NULL AND lease_until IS NULL)),
  CHECK (fencing_token=attempts),
  CHECK (last_error IS NULL OR last_error REGEXP '^[A-Z][A-Z0-9_]{0,63}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='可恢复后台任务与单调令牌租约表';

-- 升级不能遗漏V007已存在的尝试；只补业务引用，不伪造资金或渠道结果。
INSERT INTO tc_job_lease(tenant_id,job_type,business_key,status,next_run_at,created_at,updated_at)
SELECT tenant_id,'PAYMENT_RECOVER',merchant_request_no,'READY',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)
FROM tc_payment_attempt;
