-- 租户流水游标先限量再关联商户，避免过滤稀疏渠道时扫全账本。
ALTER TABLE tc_money_ledger ADD INDEX ix_money_ledger_scan(tenant_id,id);
