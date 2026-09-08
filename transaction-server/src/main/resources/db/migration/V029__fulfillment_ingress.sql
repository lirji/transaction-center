CREATE TABLE tc_fulfillment_ingress (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '来源回执内部主键',
 tenant_id BIGINT UNSIGNED NOT NULL COMMENT '已验签调用者租户',
 actor_key CHAR(64) NOT NULL COMMENT '真实签发方与主体联合摘要',
 operation VARCHAR(24) NOT NULL COMMENT '固定交付完成确认收货或退货操作',
 source_request_id VARCHAR(128) NOT NULL COMMENT '操作内永久来源事件，不是短期HTTP缓存',
 request_hash CHAR(64) NOT NULL COMMENT '规范业务内容摘要，不包含HTTP键或服务端时间',
 observed_at DATETIME(3) NOT NULL COMMENT '首次受理服务器时间，永久重放不重取时间',
 result_no VARCHAR(64) NULL COMMENT '首次结果履约申请或独立验收资源号',
 result_status VARCHAR(24) NULL COMMENT '首次返回业务状态',
 result_version BIGINT UNSIGNED NULL COMMENT '首次返回资源版本',
 PRIMARY KEY(id),
 UNIQUE KEY uk_fulfillment_source(tenant_id,actor_key,operation,source_request_id),
 CHECK(operation IN('DELIVER','COMPLETE','CONFIRM','RETURN_CREATE','RETURN_INSPECT')),
 CHECK((result_no IS NULL AND result_status IS NULL AND result_version IS NULL) OR (result_no IS NOT NULL AND result_status IS NOT NULL AND result_version IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='履约及退货入口永久来源回执，与领域事实审计同事务提交';
CREATE TABLE tc_return_request (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '退货申请内部主键',
 tenant_id BIGINT UNSIGNED NOT NULL COMMENT '申请所属可信租户',
 return_no VARCHAR(64) NOT NULL COMMENT '对外退货申请RMA编号',
 order_id BIGINT UNSIGNED NOT NULL COMMENT '原订单主键，不依靠外键改变锁序',
 order_item_id BIGINT UNSIGNED NOT NULL COMMENT '原冻结实物明细',
 warehouse_id BIGINT UNSIGNED NOT NULL COMMENT '本批实际接收仓库',
 quantity BIGINT UNSIGNED NOT NULL COMMENT '本申请整批待接收数量，申请本身不改库存',
 status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT '待验收或已整批验收',
 receipt_no VARCHAR(64) NULL COMMENT '验收后独立库存实收事实编号',
 version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '验收If-Match条件版本',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '申请创建UTC时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最近更新UTC时间',
 PRIMARY KEY(id),UNIQUE KEY uk_return_request(tenant_id,return_no),
 CHECK(quantity>0),CHECK(status IN('OPEN','INSPECTED')),
 CHECK((status='OPEN' AND receipt_no IS NULL) OR (status='INSPECTED' AND receipt_no IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='退货意图与实际验收分离的RMA申请，只有独立验收能改变库存';
