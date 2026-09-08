# 对账资金事实导出实施合同

依赖已有 tc_money_ledger 的不可变收款/退款流水，禁止以订单净消费或可变退款预占余额作为渠道对账资金真值。recon-platform 仍负责比对；本切片只提供可信租户与商户绑定范围的事实页，后续独立受理差异及安全查单调度。

## 数据与边界

支付域新增 ReconciliationFactsPort 与 Mapper，使用资金流水为驱动表，通过同租户 receipt、attempt、merchant_channel 的原绑定确定 channelId。退款流水关联同租户 receipt/refund_no 的成功 hold；只输出真实成功退款，不把 HELD/SUBMITTED/RELEASED 当资金流。包含 ledgerId（字符串）、ledgerNo、方向、正数最小单位金额、币种、receiptNo、refundNo、provider交易号/退款号、原订单号、收款 disposition、渠道发生时间与本地确认时间。收款异常分类必须保留，不仅 NORMAL。退款不能把原支付渠道时间冒充退款时间。

不得导出 credential_ref、回调原文、地址、会员身份或任意 evidence URL。channelId 是本租户不可变商户绑定，不接受用户输入渠道商户号替代校验；冻结绑定仍可查历史资金。未知/跨租户绑定拒绝。接口权限使用独立 trade.reconciliation.read；原 trusted service 身份绑定方案需在公开前复用或明确补齐，不能用任意 admin 直接授权财务导出。

## 游标与完整性

单页独立主库 RR 只读事务：校验绑定、第一次取当前租户已提交 ledger 最大 ID、固定上界。先使用新增 (tenant_id,id) 覆盖索引读取 after < id <= upper 的最多 scanLimit+1 个流水 ID（scanLimit<=100），再按这最多100个ID读取详情并过滤原 channelId。返回本页读取时间、upper、nextCursor、hasMore 和 scannedCount。nextCursor推进已扫描最后ID，不是返回结果最后ID；hasMore表示租户候选仍有下一页。因此允许结果为空但hasMore=true，调用者必须按扫描游标继续，不能以空列表停止。所有ID在JSON表示字符串。

选择该方式是避免对不可变账本追加冗余绑定和历史回填，同时保证稀疏/空商户绑定也只有有限数据库工作量。不能在跨表渠道过滤后加LIMIT就声称有界；这种方案可能扫描全租户账本。固定租户上界本身只是该租户数据范围元信息，不暴露其他渠道金额或流水详情；调用者属于受信财务服务且绑定租户。若未来需要绑定专属快照或严格不暴露租户上界，应使用持久导出批次与不透明游标，不能静默复用本接口。后续页必须继续原上界；校验 after<=upper、正数绑定/租户、scanLimit1..100。查询候选/详情均在同一页事务快照内。

有限分页不能证明跨页全局快照，也无法靠自增 ID 捕获迟提交低 ID。响应必须明确 coverage=BOUNDED_SCAN，完整对账需要在业务结算窗口关闭后重复从 0 读取、按 ledgerNo 永久去重，并由 recon 结合渠道账单完整性/迟到策略确认封账。不能把扫描结束标记成 READY/全部已对齐。下一阶段需要可靠增量订阅或冻结导出批次时再实现持久快照/manifest，不能把本页查询冒充这些能力。

## 验收

真实数据库覆盖正常/异常收款、部分及多次成功退款、在途退款不导出；同一原渠道绑定历史冻结可读、跨租户/错绑定拒绝；页大小/游标参数边界、限界后新流水留下一轮、迟提交低 ID 在新轮可读；输出不含凭据及原文。HTTP需实际认证/授权用例，OpenAPI及Mapper门禁同步。对账受理/处置不在只读导出中暗中执行，资金约束不变。

真实渠道账单格式、结算时区、手续费及修订合同仍待外部确定；不会编造下载接口成功或空账单。后续实现前独立审查上述查询方案的索引容量与分页口径。

## 独立设计审查决议

审查指出渠道过滤后LIMIT以及渠道内MAX均无法保证稀疏绑定下的扫描成本，已采用先限租户候选ID再过滤方案；这是首次内部接口设计，无既有外部分页合同变更。SQL候选不得携带渠道JOIN，详情限定至已取ID集合并核原绑定。退款hold用tenant+refund_no+receipt_id共同绑定，时间取hold.provider_succeeded_at/confirmed_at。
