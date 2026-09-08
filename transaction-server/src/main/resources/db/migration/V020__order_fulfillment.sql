-- 首期整单全量实际交付，支付时间不能充当物流/服务交付证据。
CREATE TABLE tc_order_fulfillment (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '履约事实内部主键',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '可信来源所属租户',
  order_id BIGINT UNSIGNED NOT NULL COMMENT '原冻结订单主键，首期一单一履约',
  fulfillment_no VARCHAR(64) NOT NULL COMMENT '永久履约业务编号',
  source_actor_key CHAR(64) NOT NULL COMMENT '可信交付来源身份摘要，不保存访问令牌',
  source_request_id VARCHAR(128) NOT NULL COMMENT '来源永久交付事件编号，不依赖短期幂等记录',
  delivery_ref VARCHAR(255) NOT NULL COMMENT '整单实际交付证据引用，不保存收货个人明文',
  delivered_at DATETIME(3) NOT NULL COMMENT '实际整单交付UTC时间，不等于支付或商业完成时间',
  delivery_hash CHAR(64) NOT NULL COMMENT '绑定交付来源事件、订单和实际交付内容的摘要',
  completion_actor_key CHAR(64) NULL COMMENT '可信商业完成来源身份摘要',
  completion_request_id VARCHAR(128) NULL COMMENT '来源永久商业完成事件编号',
  completion_ref VARCHAR(255) NULL COMMENT '独立商业完成证据引用',
  completed_at DATETIME(3) NULL COMMENT '独立商业完成UTC时间，不能早于实际交付',
  completion_hash CHAR(64) NULL COMMENT '商业完成事件不可覆盖的内容摘要',
  created_at DATETIME(3) NOT NULL COMMENT '交付事实本地提交UTC时间',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '履约事实版本，商业完成时递增',
  PRIMARY KEY (id),
  UNIQUE KEY uk_fulfillment_order (tenant_id,order_id),
  UNIQUE KEY uk_fulfillment_number (tenant_id,fulfillment_no),
  UNIQUE KEY uk_fulfillment_source (tenant_id,source_actor_key,source_request_id),
  UNIQUE KEY uk_fulfillment_completion (tenant_id,completion_actor_key,completion_request_id),
  CONSTRAINT fk_fulfillment_order FOREIGN KEY (tenant_id,order_id) REFERENCES tc_order(tenant_id,id),
  CONSTRAINT chk_fulfillment_delivered CHECK (delivered_at<=created_at),
  CONSTRAINT chk_fulfillment_completion CHECK (
    (completed_at IS NULL AND completion_actor_key IS NULL AND completion_request_id IS NULL AND completion_ref IS NULL AND completion_hash IS NULL)
    OR (completed_at IS NOT NULL AND completed_at>=delivered_at AND completion_actor_key IS NOT NULL AND completion_request_id IS NOT NULL AND completion_ref IS NOT NULL AND completion_hash IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='整单实际交付及独立商业完成事实，作为退货和活动资格的履约前置';
