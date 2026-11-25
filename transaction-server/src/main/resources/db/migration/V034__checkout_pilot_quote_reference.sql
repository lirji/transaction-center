CREATE TABLE tc_checkout_pilot_quote_ref (
  quote_ref CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '交易服务生成的不可变试点报价引用',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '获得试点授权的交易租户',
  commerce_quote_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '经核验的商城报价标识',
  owner_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '限定于该商城报价的主体证明摘要',
  scope_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已核验的试点范围版本',
  store_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '经核验的商城门店',
  evidence_json JSON NOT NULL COMMENT '核验时持久化的商城门店及商品版本分值证据',
  expires_at DATETIME(3) NOT NULL COMMENT '商城报价的UTC失效时间',
  created_at DATETIME(3) NOT NULL COMMENT '交易端首次持久化UTC时间',
  PRIMARY KEY (quote_ref),
  UNIQUE KEY uk_pilot_source_quote (tenant_id, commerce_quote_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='试点报价准备凭证，后续与冻结交易报价和原生订单关联';
