# 支付可信回调耐久接收与处理

## 已批准范围中的本切片

依据原FINAL_PLAN 6.2、DATABASE_DESIGN tc_payment_callback及TC-12／13／18／32，实现可信适配器之后的密文耐久接收和带租约业务处理。它不是渠道验签器：调用方必须先验证原始报文签名、证书有效性、商户归属和可靠关单语义；不能把客户端提交的“已验签”标记当可信依据。当前仍无公开callback HTTP入口，真实渠道适配／应答协议单独接入，不修改前端。

## 流程

1. PaymentCallbackService.accept接收可信适配器的不可变VerifiedPaymentCallback，在显式短事务中校验尝试所属租户及渠道商户，释放读连接后生成必要原文摘要、解析结果摘要和本地回调号。禁止在NEVER的空同步上下文中先裸调Mapper再开写事务，否则可能一请求同时占读连接并等待写连接。
2. 事务外AES-256-GCM保存必要原文密文，AAD绑定租户／回调号／尝试号／渠道／事件号／解析摘要／证书版本／原文摘要，密钥版本也受认证保护。密钥只有外部注入的版本环，无默认密钥；新报文用活动版本，历史版本保留用于处理积压。此适配器不等于生产KMS。
3. 自有短事务写tc_payment_callback和PAYMENT_CALLBACK任务，唯一事件键重放只返回原编号，不覆盖原文。相同事件键不同原文或解析摘要拒绝；成功返回仅表示接收已提交，不代表已入账。
4. PaymentCallbackWorker按租户领取任务，调用PaymentCallbackPort.process，校验密文与摘要后处理RECEIVED或可靠CLOSED。业务副作用／回调处理标记／租约完成同事务，失效令牌全部回滚，但此前接收密文仍在，可被新Worker恢复。
5. 暂时数据库／密钥不可用：原任务有界退避并保留事实；确定的证据冲突进入REVIEW，不能把它当资金失败或释放库存。缺实际原始验签／来源材料时不得调用可信接收端口。

事务开启或提交异常也仅重调原任务。提交应答丢失可能实际已提交；若任务已DONE，重试CAS失败返回LEASE_LOST，不重启它或再次入账。退避落库本身若仍不可用则抛错并依赖租约到期接管；后续调度器必须持续运行，不能一次异常后永久停止。

退避为5秒起指数上限加半区间抖动，最长5分钟；runBatch逐个领取，批次1～100，避免大批租约排队到期。完成事务10秒，单任务租约30秒；这些是初始配置，不是容量或SLA认证。

## 配置

`TRADE_CALLBACK_ACTIVE_KEY_VERSION`指定活动版本；`TRADE_CALLBACK_KEYS`为外部Secret注入的版本:Base64键值对，以逗号分隔，每个密钥32字节，禁止重复版本。为空允许其他业务启动，但回调接收失败关闭。版本名仅字母数字、下划线和短横线；不允许修改旧版本对应的密钥内容。

轮换顺序：先分发新旧密钥环，再切换活动版本，待旧积压及保留期内证据完成迁移后才可受控移除旧版。当前没有自动重加密或销毁任务，不能直接删掉仍需解密的历史密钥。

payload为适配器已验证且业务必须保留的原始字节，长度1～65536；不得用重新序列化的解析JSON冒充原始签名输入。具体渠道的签名头／证书证据封装及保存方式仍须在适配合同中明确，当前内部测试输入不是实际渠道报文。

## 模块与接口

- payment：VerifiedPaymentCallback、CallbackRecord、EncryptedCallback、CallbackPayloadProtector及AES实现；PaymentCallbackService和公共PaymentCallbackPort；CallbackMapper＋XML。
- jobs：PaymentCallbackWorker通过公开Port调用支付，不跨域Mapper；JobType增加PAYMENT_CALLBACK。任务完成进一步核对类型和业务引用，不能拿合法租约配另一个回调号提交。
- V009：tc_payment_callback及明确命名的任务类型CHECK扩展，不修改V001～V008。
- 内部入口：accept(tenant, verified)、process(tenant, callbackNo)、markReview(tenant, callbackNo, code)、Worker.processLease／runBatch。无新增HTTP API，不泄露原文或密钥。

## 本切片验收计划

本地实际结果：build-callback-release.log 对应 mvn verify 退出0，95项通过且无失败／错误／跳过；原始7份报告归档 evidence/callback-inbox。V009共享迁移和validate均成功，全库29表／372字段中文注释完整（含Flyway历史表），回调／尝试／任务0行。以下案例证明内部机制，真实验签与外部故障验收仍未完成。

- CB-01：接收只存密文和任务、不直接入账；并发重放唯一、冲突不覆盖。
- CB-02：数据库接收失败不能返回成功，任务失败时接收同事务回滚。
- CB-03：入账后提交前失败、旧租约接管，业务和处理标记回滚而接收记录仍在。
- CB-04：处理100次重复／多个事件同交易号，只正常确认一次；异常金额保留真实资金。
- CB-05：错商户／租户／尝试拒绝；密文／归属／解析摘要被改拒绝并待核对。
- CB-06：活动密钥轮换后旧版本可解密，缺历史密钥只重试，不丢弃证据；日志对象脱敏。
- CB-07：可靠关闭处理仅在可信来源合同下执行；原始验签、渠道超时及完整断响应HTTP场景仍需真实适配器验证。

## 运维与尚未完成

无自动扫描全部租户或常驻调度，runBatch只提供受控批次执行。事务外调用编排及订单取消／过期持久唤醒已在后续切片补充，见PAYMENT_RECOVERY_WORKER.md和ORDER_RESOLUTION_PLAN.md。实际Worker部署／指标报警／队列公平性、真实渠道证书与KMS托管、冲突告警、证据归档和合规保留期、真实查单网络／退款仍未完成。生产禁止配置模拟回调密钥或将单机测试称为三高验收。
