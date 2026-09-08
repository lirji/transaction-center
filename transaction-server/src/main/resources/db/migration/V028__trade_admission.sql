CREATE TABLE tc_admission_policy (
 tenant_id BIGINT UNSIGNED NOT NULL COMMENT '可信租户主键，缺省租户允许新增受理',
 order_enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否允许受理新订单，原幂等响应不受影响',
 payment_enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否允许新增支付意图或尝试，不撤销已受理计划',
 version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '管理更新条件版本，防止旧策略覆盖',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次策略创建UTC时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最近策略更新时间',
 PRIMARY KEY(tenant_id),
 CHECK(order_enabled IN(0,1)),CHECK(payment_enabled IN(0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='租户新交易紧急准入策略，保留回调查询退款及已受理任务恢复';
