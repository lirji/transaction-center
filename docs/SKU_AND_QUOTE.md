# 多SKU与服务端报价

## 已实现流程

后台创建商品（含主SKU）→为草稿新增／编辑规格→发布全部SKU不可变快照→已注册会员选择SKU和数量→服务端固定价策略生成不可变报价→本人查询原报价。

报价不扣库存，不是支付成功或首单证据。后续订单通过QuotePort在同一本地事务核验报价归属、会员状态、商品当前可售性和有效期，再复制报价快照、预占库存。订单基础已在后续切片实现，见[订单基础](ORDER_FOUNDATION.md)；完整支付及裂变验收仍未完成。

## 新增API

基路径为/api/v1/trade，准确类型与错误合同见[OpenAPI](openapi.json)。

| 方法与路径 | 权限 | 内容 |
|---|---|---|
| POST /admin/products/{id}/skus | trade.admin | expectedVersion、skuNo、unitPrice、currency、stockType、specification |
| PATCH /admin/products/{id}/skus/{skuId} | trade.admin | expectedVersion及目标规格属性，不修改规格业务编号 |
| GET /admin/products/{id}/skus | trade.admin | 草稿列表，revision为整体商品版本 |
| GET /products/{id}/skus | 已登录 | 当前已发布版本列表，下架／不存在返回空列表 |
| POST /quotes | 已注册会员 | items，每项只有skuId字符串和quantity整数，返回完整报价 |
| GET /quotes/{id} | 报价所属会员 | 包括过期报价的只读历史，不续期 |

新增写入口均需Idempotency-Key。管理写操作继续采用现有200／resourceId及body expectedVersion兼容合同；报价创建返回200和完整快照，不接受客户端金额、币种、会员或归因令牌。正式V1的201／If-Match统一工作仍保留。

商品最多100个SKU，每规格最多16项属性，名称最长64、值最长128；空规格传空对象。库存类型PHYSICAL／SERVICE。原商品编辑入口只更新最早创建主SKU价格，其余规格必须使用明确的SKU编辑入口。

## 报价规则与并发

- 每报价1～50个不同SKU，每项数量1～1,000,000,000；SKU编号为无前导零的正整数long字符串。
- 全部SKU来自同一租户、必须已发布、币种一致；不把不同币种金额相加。
- PricingPolicy为策略扩展点；当前仅FIXED_PRICE_V1。金额使用整数乘加并检查溢出，总额和明细不超过9,007,199,254,740,991；零金额允许报价，但后续零元订单不得伪造付款或占消费首单。
- 数组按数值SKU ID排序后计算幂等摘要；换序不冲突，数量或SKU改变产生409。相同SKU重复出现直接400，不自动合并。
- 同键重放返回原报价，商品涨价、会员冻结或时间推进均不重新计价／续期。若原报价过期，需要新的业务动作键重新报价。
- 首次创建持有会员行锁和商品发布共享锁；冻结与创建有明确顺序。幂等记录、报价主表、所有明细及审计在同一事务提交。
- 取得全部锁后读取UTC业务时钟，精确到毫秒；有效期默认300秒，配置trade.quote.ttl-seconds允许1～900，错误配置启动失败。
- QuotePort.requireUsable必须在事务中调用，校验当前商品仍可售，但不替换旧报价价格。全部锁等待结束后再次校验时间，now等于expiresAt即QUOTE_EXPIRED。
- 历史查询允许冻结会员读取本人报价；不同会员及跨租户对象统一404。查询不是资格预占。

## 持久化与回滚

V004在SKU草稿／发布快照中增加specification、stock_type，并创建tc_quote和tc_quote_item，全部带中文表／字段注释。QuoteMapper.xml保存绑定SQL，模型和UTC／JSON结果显式映射；主表插入后同一连接立即读取LAST_INSERT_ID，尚未写入明细或审计，避免错误取号。

没有覆盖或删除报价的HTTP入口。重试只能读取原快照。订单基础已以报价唯一约束确保一份报价只建一单，不能依赖客户端不重试。

部署回退不删除报价历史，也不修改已经执行的V001～V004；必要时使用后续兼容迁移。共享库具体执行状态以[验证记录](VERIFICATION.md)及Flyway历史为准。

## 验收范围

覆盖30个并发同键报价请求、换序重试、价格变更／下架、过期精确边界、冻结／越权、金额溢出、混币、重复／超限数量、外层回滚、HTTP未知字段及小数拒绝。JSON小数到整数的隐式截断已全局禁用，旧金额／版本入口也受保护。

本地集成测试不是容量或高可用证明；完整订单、支付、首单事实、退款和裂变联调仍须继续完成。
