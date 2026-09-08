# 退款回调持久接收与租约消费

状态：V015已通过207项全量测试并共享迁移；36表/495字段中文注释完整。不得据此宣称已完成真实供应商退款。沿用完整退款计划TC21/22/25/29及原生产门禁，证据见DELIVERY_STATUS/VERIFICATION及任务档案evidence/refund-inbox。

## 实现流程

1. 可信适配器完成原始验签或认证查询，生成VerifiedRefundEvidence；普通HTTP请求不能直接提供成功事实。
2. RefundInboxService在独立短事务读取原退款绑定，核对租户/渠道/商户/退款号。禁止带入外层业务事务。
3. 通过核心EvidenceProtectionPort加密必要原文。服务装配层EvidenceProtectionAdapter复用已有CallbackPayloadProtector；没有新默认密钥，没有改变旧支付密文格式，也不允许退款引用支付实现类。
4. 插入tc_refund_callback、当前读唯一事件、比较原文及解析事实摘要、入队REFUND_CALLBACK同事务提交。收到原始证据不能因为发起请求的租约已经失效而丢弃。正常返回只确认持久接收。
5. RefundCallbackWorker逐笔领取租约，事务内认证证据、核对原资金、完成退款应用/资金/组件/订单与Inbox标记，最后jobs.complete校验fencing。失败全部回滚。
6. 可信异常或矛盾资金证据转REVIEW，数据库/事务/历史密钥暂不可用则有界退避重试。REVIEW或RETRY不代表资金失败，不释放UNKNOWN额度。

主动退款发起/QUERY恢复、审批定时到期、受认证同号安全重放的REFUND_RECOVER仍须下一阶段实现。不能把本回调消费Worker当作主动退款恢复已经完成。

## 存储和不变量

- V015新增tc_refund_callback，26字段全部中文注释；增加REFUND_CALLBACK任务白名单，不改V001～V014历史。
- 全局(channel_code,channel_merchant_no,provider_event_id)永久唯一；本地(tenant_id,callback_no)唯一。相同事件不同原文或不同解析结果拒绝，不覆盖首个事件。
- 按已冻结merchant_refund_no定位退款；同号错误金额/币种/原扣款号仍耐久接收，处理时隔离核对。不把异常证据悄悄过滤。
- 成功必须包含渠道退款号、正金额、币种、发生时间；FAILED_FINAL不得携带成功资金字段，且必须有可信终态发生时间。HTTP200/不存在/本地TTL不构成FAILED_FINAL。
- AAD包含用途、租户、接收号、退款号、渠道商户、事件、事实摘要、验签版本和原文摘要；原文1～65536字节，随机Nonce，版本密钥可轮换。原文副本用后清零；DTO、密文及持久模型默认日志脱敏。
- 证据处理不先持Inbox行锁再取订单锁。资金锁顺序保持订单→支付意图/实收→hold→申请/组件→Inbox状态→任务fencing。
- FAILED后矛盾成功保留原始证据并进入REVIEW，不重新占款；原已退/预占、库存及everPaid不被伪改。核对与受控补账仍是后续运营能力，不能自动把矛盾资金当已处理。
- 已完成或REVIEW任务不会被重复接收重启；任务和Inbox之间永久关联，不依赖HTTP幂等快照保留期。

## 运行配置

原worker.enabled仍默认false。显式worker角色增加退款回调独立通道，配置trade.worker.refund-callback-threads / TRADE_WORKER_REFUND_CALLBACK_THREADS，默认1；每通道1～8，总槽位含新通道不超过16。保持单任务领取、30秒租约、10秒本地事务、有界停机及租户轮转。禁止直接部署后在共享库试跑业务。

仍复用TRADE_CALLBACK_KEYS及TRADE_CALLBACK_ACTIVE_KEY_VERSION。装配层使用领域命名空间隔离用途；未宣称托管KMS或独立生产密钥策略已经完成。接收器存在不等于数据库和密钥永远可用，真实渠道上线前须完成配置校验、故障应答和网络持久重试验收。

## 验收证据

FoundationTest#refundInbox*的13项测试在最终全量中全部通过，覆盖20并发接收/10并发处理、同事件原文/事实冲突、跨租户及跨退款拒绝、入队失败接收回滚、禁止外层事务、异常金额/币种/原扣款号、可靠失败后矛盾成功、陈旧租约成功及REVIEW回滚、伪造任务引用、密文篡改/跨用途移植/伪造事实重算摘要、历史密钥恢复、提交确认丢失、真实持久接收Port交接。它们验证本地内部合同，不替代真实网络与供应商认证。

WorkerRuntimeTest补第四通道装配和启动检查；MapperContractTest/QualityGateTest继续强类型XML、租户及中文注释门禁。真实供应商验签HTTP、断网实测、生产队列延迟/吞吐/容灾、审批身份、净支付正式资格和完整裂变链路仍未完成。

## 升级与回退

先在隔离MySQL通过完整迁移与测试，再向已授权transaction_center共享开发库执行V015并validate及注释巡检。旧程序不识别新JobType，不能运行旧Worker领取新类型；先停旧Worker、迁移、上线新Worker。应用回退先停止新版Worker并关闭回调入口，保留证据与任务，不删表或回滚资金；当前没有单通道停用开关，不能设置线程数0冒充停用。需要回退至旧枚举版本时须隔离退款回调任务，不能忽略枚举解析失败。
