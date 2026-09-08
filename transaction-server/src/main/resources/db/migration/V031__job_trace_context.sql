-- 新任务捕获首次因果上下文，旧任务NULL保持原状；追踪不参与租约或资金裁决。
ALTER TABLE tc_job_lease
 ADD COLUMN trace_parent VARCHAR(55) NULL COMMENT '首次入队W3C00版追踪父头，旧任务无上下文为NULL',
 ADD CONSTRAINT chk_job_trace_parent CHECK (trace_parent IS NULL OR (
   REGEXP_LIKE(trace_parent,'^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$','c')
   AND SUBSTRING(trace_parent,4,32)<>'00000000000000000000000000000000'
   AND SUBSTRING(trace_parent,37,16)<>'0000000000000000'));
