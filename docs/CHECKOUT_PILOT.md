# 店铺试点结算报价

商城先从持久化的 WEB 报价核验商户、门店和商品版本。交易中心随后为同一商城报价保存一份来源证据，使用 V034 的唯一约束保留首次引用。来源证据能够复查，后续冻结交易报价与原生订单时继续使用该引用。

## 准备入口

`POST /internal/v1/checkout-pilot/quote-references` 使用 `trade.checkout-pilot.prepare` 作用域。请求为 `commerceQuoteId` 和 `ownerRef`。调用方须是结算服务在已认证买家上下文中选定的主体和原报价。

交易验证 JWT 和固定机器身份，向配对的商城请求 `GET /internal/v1/checkout-pilot/quotes/{quoteId}`，核对范围版本、两侧租户、商城商户和门店、参与 SKU、报价与主体、有效期及 CNY 购物篮。当前最多接受 50 种商品。首次返回 `tradeQuoteRef`、`commerceQuoteId`、`scopeVersion`、`storeId`、`sourceDigest` 和 `expiresAt`。其中 `sourceDigest` 是交易序列化的商城快照 SHA-256 小写十六进制值，由调用方原样交给营销签名。有效期内使用同一商城报价与主体重试可读取原回执。

主体或租户不符返回 403，来源不符或过期返回 409，商城不可用返回可重试的 503。同一报价并发提交只写一条记录。

## 冻结入口

`POST /internal/v1/checkout-pilot/quote-references/{reference}:freeze` 使用 `trade.checkout-pilot.freeze` 作用域和同一授权机器。请求仅包含 `checkoutQuoteToken`，取自营销试点求值。交易验签并比对两侧租户、范围版本、营销组织与店铺、活动、出资方、报价主体和有效期。首次冻结前重新向商城核验报价，逐项比对来源摘要、购物篮、SKU、原价、数量与应付。未参与的 SKU 保持原价，原价减优惠必须等于应付。

签名用途为 `marketing.checkout-price/v1`。各段采用无填充的 Base64 URL 编码。头为公钥编号，负载为按字段名升序排列、以字节数标注字段名及值长度的 ASCII 记录，签名为头与负载之间以点号连接后的 Ed25519 签名。购物篮行以 SKU 作为行键，每行使用 `行键~SKU~数量~单价分~应付分`，按行键升序以逗号分隔。签价保存营销原报价、活动代次、发布组合与权益版本用于追溯。

回执包含 `tradeQuoteRef`、`currency`、`grossMinor`、`discountMinor`、`payableMinor` 和 `expiresAt`，金额以字符串返回整数分。V035 允许旧引用保留未冻结状态，并约束新冻结记录金额和过期时间。首次凭证通过原子条件更新保存，仍有效的相同签名重放返回同一笔金额。异签、失效、金额冲突或商城来源变更返回 409，引用缺失返回 404。

## 配置

- `TRADE_CHECKOUT_PILOT_ENABLED` 默认关闭。启用时设置 `TENANT_ID`、`CALLER_KEY` 和 `SCOPE_VERSION` 对应的同名前缀配置。调用方键使用现有 `Actor.key()` 约定。
- `COMMERCE_BASE_URL` 和 `COMMERCE_TOKEN` 使用同一前缀。商城凭据从受控配置读取。对端使用 HTTPS，回环隔离可以使用 HTTP。
- `COMMERCE_TENANT`、`MERCHANT_ID`、`STORE_ID` 和 `SKU_ID` 采用已签收配对。配置缺失或格式无效会在启用时启动失败。
- `TRADE_CHECKOUT_PILOT_FREEZE_ENABLED` 默认关闭。启用时配置同一前缀的 `MARKETING_KEY_ID`、`MARKETING_PUBLIC_KEY`、`MARKETING_TENANT`、`MARKETING_ORGANIZATION`、`MARKETING_SHOP`、`MARKETING_CAMPAIGN` 和 `MARKETING_FUNDER`。公钥使用标准 Base64 编码的 X.509 Ed25519 公钥。私钥只由营销签价服务持有。

## 原生下单

买家以交易 JWT 调用 `POST /api/v1/trade/checkout-pilot/orders`，提交 `Idempotency-Key` 和 `tradeQuoteRef`。实物订单同时使用已有 `warehouseId` 字符串和 `address` 对象。原价、优惠和商品一律由服务端确定。交易以配置的商城会员映射重建报价主体摘要，并重新核对商城的门店与已发布商品。商城 SKU 按明确的一对一映射查找交易当前已发布规格，单价和币种须一致。本交易租户的有效收款商户也必须命中配对。

V036 在一个本地事务内保留原生报价、逐行原价与折扣、原生订单、库存预占及唯一报价关联。同一买家、报价及履约参数可重试，返回同一订单。报价变更、过期、使用旧入口消费试点报价或请求参数冲突时拒绝。库存失败回滚全部写入。买家仍通过标准订单接口读单和取消。

`GET /internal/v1/checkout-pilot/quote-references/{reference}/order-evidence` 要求 `trade.checkout-pilot.order-evidence` 作用域与已配对机器。返回交易租户、试点引用、订单及原生报价编号、范围和商城门店、签名摘要、币种与三个整数分值、订单状态及到期时间。未绑定订单返回 404，响应禁止缓存，会员和地址留在交易。

新入口使用 `TRADE_CHECKOUT_PILOT_ORDER_ENABLED`，默认关闭。启用时同时配置同一前缀的 `NATIVE_MERCHANT_NO`、`MEMBER_LINKS`（交易会员主键到商城会员标识的 JSON 对象）和 `SKU_LINKS`（商城 SKU 到 `commerceRevision`、`tradeSkuId`、`tradeRevision` 的 JSON 对象）。买家及规格一对一，分别至多 1000 位与 50 个。两侧规格版本均须匹配，同价重发也需要更新配对。映射由实际责任方确认后通过受控部署配置注入。

预算回执接通前，试点原生订单进入待支付，尝试支付返回 409 和 `CHECKOUT_PILOT_FUNDING_PENDING`，不会生成意图或渠道请求。仅关闭新下单开关时，已有试点订单依旧受付款拦截，获授权机器仍可查询证据。部署启用前须确认每个支付实例已支持门禁。若要回退到缺少该门禁的应用版本，先停止新下单并取消待支付试点订单，再核对库存释放和付款状态。
