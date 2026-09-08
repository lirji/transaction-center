库存完整快照 V1。minimal/complete 均使用全部必填字段，complete 展示跨仓 OUT 流水号。invalid-balance 必须被 typed codec 拒绝。库存为 int64，消费者必须保留整数精度，不能使用浮点数累加。当前黄金测试验证实际 Java codec；未声称执行 JSON Schema 引擎。
