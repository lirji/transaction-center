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

停用后保留已有审计。原生订单与报价关联、资金预留和付款门禁由后续切片接入。
