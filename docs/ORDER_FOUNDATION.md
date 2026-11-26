# 订单与库存事务基础

> 时效说明（2026-09-08）：本文的“交付边界”记录 V005 初期范围。当前已实现支付/退款本地核心、取消恢复 Worker、履约/退货入口；真实渠道与生产来源仍需接入。当前完整流程见 [业务说明](BUSINESS_GUIDE.md)，取消恢复见 [订单恢复](ORDER_RESOLUTION_PLAN.md)。

## 交付边界

新增transaction-order模块。当前实现逻辑商户配置、实物／服务订单创建、本人列表详情、取消、支付许可屏障和地址密文；没有真实支付发起、收款确认、退款、履约完成或自动关单工作者。CLOSING仍需后续支付模块取得可靠终态后处理，不能人工强制释放。

原交易生产设计TC-04／07／08／30等获得局部或直接测试证据，但完整TC-06千并发、支付关单网络交错、首单及裂变发奖仍待完成。前端按[OpenAPI](openapi.json)接入，不显示未实现的支付按钮。

## 请求链路

管理员配置逻辑商户→商品发布→会员报价→下单引用quoteId→锁定会员及可售商品→锁定商户→生成订单与密文地址→逐SKU预占→审计和Outbox→共同提交。

报价、订单及库存采用单库本地事务，任意SKU失败全部回滚。库存锁按报价明细数值SKU升序取得，同订单仅一个仓库。一个报价只能建一单，数据库唯一键防止换幂等键绕过；同键重放返回原订单，改地址或引用产生409。

## API

基路径/api/v1/trade：

| 方法与路径 | 权限 | 行为 |
|---|---|---|
| POST /admin/merchant | trade.admin | 明确merchantNo、name，租户内仅一份配置，不覆盖既有编号 |
| GET /admin/merchant | trade.admin | 读取本租户配置，不包含渠道密钥 |
| POST /admin/merchant:change-status | trade.admin | expectedVersion、targetStatus、reason；冻结禁止新订单 |
| POST /orders | 会员 | quoteId字符串、实物warehouseId字符串及address；金额、商户、归因引用均不接受 |
| GET /orders | 会员 | after／limit有界读取本人订单主表，不逐单加载明细 |
| GET /orders/{orderNo} | 会员 | 订单及不可变商品快照，不包含地址明文 |
| POST /orders/{orderNo}:cancel | 会员 | reason；返回CANCELLED或CLOSING，后者不代表已释放 |

当前兼容阶段返回200和实际对象／引用；正式V1的201／202及If-Match对齐仍保留。新写接口均需Idempotency-Key，错误沿用统一code／traceId／retryable合同。

逻辑商户不是支付渠道账户。管理员配置不能替代真实渠道商户授权；渠道模块必须核对逻辑商户与已认证渠道账户映射，不得仅凭字符串收款。

## 支付与取消竞争

- 无支付许可：取消持有订单行锁，状态直接CANCELLED，与所有库存释放同事务提交。
- 有支付许可：取消只能CLOSING，保留预占；即使已过expiresAt也不能按本地时间释放。
- OrderPaymentPort.authorizePayment必须在事务内调用，订单仍待付且未过期才允许置位payment_started_at；许可和后续支付意图必须同事务提交，网络调用在事务外。
- 后续Worker不得绕开该许可；本阶段仅验证本地屏障，不能宣称已经证明真实渠道的关单终态。
- 零元订单不调用渠道，状态PAID表示待履约，financialStatus=NO_PAYMENT_REQUIRED，paidAt保持空。实物预占立即确认；不修改everPaid或消费首单，商业完成仍需后续履约证据。
- 会员冻结仍可查看与取消已有待付订单；新订单和新支付许可要求ACTIVE。商户冻结不删除或改写历史订单的商户身份。

## 地址安全

实物订单要求recipient、phone、address，纯服务拒绝收货个人信息和仓库参数。电话号码只做格式验证，不宣称完成号码所有权验证。

AES-256-GCM每字段独立随机12字节Nonce，密文包含Nonce及认证标签；AAD绑定tenant、orderNo、字段用途。数据库保存recipient_cipher／phone_cipher／address_cipher及key_version，不保存密钥。默认订单查询不解密地址，请求对象toString脱敏；幂等摘要显式使用原字段，避免脱敏toString导致不同地址摘要碰撞。

配置：

- TRADE_ADDRESS_KEY_BASE64：外部注入的32字节随机密钥的Base64编码，不写进Git、命令参数或文档。
- TRADE_ADDRESS_KEY_VERSION：密钥版本，最长64；与密钥同时配置。
- TRADE_ORDER_TTL_SECONDS：默认900，允许1～1800；只是支付截止检查时间。

未配置地址密钥时应用可运行其他能力，实物下单返回503 ADDRESS_ENCRYPTION_UNAVAILABLE。无默认密钥、无明文回退；格式错误的配置启动失败。

当前适配器仅加载一个活动密钥版本，历史解密权限接口、密钥版本注册与轮换／重加密流程尚未交付。必须保管对应版本的密钥，禁止在完成轮换方案前替换并销毁旧密钥。生产KMS接入与权限／审计验收仍是发布门禁。

## 数据库与运维

V005新增tc_merchant、tc_order、tc_order_item、tc_order_address，全部字段及表有中文注释，外键限定租户。订单保存商户与商品金额快照，取消记录原因和版本；SQL均在Mapper XML。

不修改V001～V004，不删除历史报价／订单。共享迁移必须以全量测试成功为前提，实际结果见[验证记录](VERIFICATION.md)。当前内部v0 Outbox不是正式营销V1事件，不会直接投递到现有奖励消费方。

## 可读业务订单号

新建普通订单及试点订单由交易中心发出 `TRD-ORD-YYMMDD-NNNNNNNNNN`。业务日取持久化创建时刻的北京时间。新单的 `orderNo` 与 `businessNo` 相同。系统按随机尾段和唯一索引发号。同一个幂等请求返回已提交的原记录。历史单以新增的 `businessNo` 提供可读查询号，原 `orderNo` 继续关联地址密文、库存、支付退款和已发事件。

Flyway V044 建立空字段、唯一号索引及业务日与尾号组合索引。历史补录按租户和主键扫描，旧写实例退出后再从起始位置追平一次。使用受控的 MySQL 连接选项文件。先运行预览，再在已批准的库与租户上追加 `--apply`。每次只补空值，打印成功批次的主键游标供 `--after` 续跑。

```sh
python3 scripts/backfill-business-numbers.py --dataset trade-orders \
  --database transaction_center_dev --tenant 1 --defaults-file /path/to/mysql-client.cnf
```

商城独立运行的本地订单选择 `--dataset commerce-orders`，并提供对应商城数据库及租户。历史试点资金申请的营销展示号可由原结算机器根据现有报价引用通过 `reconcile?refreshOrderNumber=true` 从交易原生证据补录。先核对目标租户和待补数量，再核验原号、新号、支付及退款关联。
