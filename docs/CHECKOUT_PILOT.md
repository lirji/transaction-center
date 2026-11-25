# 试点报价引用准备

商城先从持久化的 WEB 报价核验商户、门店和商品版本。交易中心随后为同一商城报价保存一份来源证据，使用 V034 的唯一约束保留首次引用。来源证据能够复查，后续冻结交易报价与原生订单时继续使用该引用。

## 准备入口

`POST /internal/v1/checkout-pilot/quote-references` 使用 `trade.checkout-pilot.prepare` 作用域。请求为 `commerceQuoteId` 和 `ownerRef`。调用方须是结算服务在已认证买家上下文中选定的主体和原报价。

交易验证 JWT 和固定机器身份，向配对的商城请求 `GET /internal/v1/checkout-pilot/quotes/{quoteId}`，核对范围版本、两侧租户、商城商户和门店、参与 SKU、报价与主体、有效期及 CNY 购物篮。当前最多接受 50 种商品。首次返回 `tradeQuoteRef`、`commerceQuoteId`、`scopeVersion`、`storeId`、`expiresAt`。有效期内使用同一报价与主体重试可读取原回执。

主体或租户不符返回 403，来源不符或过期返回 409，商城不可用返回可重试的 503。同一报价并发提交只写一条记录。后续冻结交易报价前重新核验门店范围和版本，并按结算契约处理优惠及资金预留。

## 配置

- `TRADE_CHECKOUT_PILOT_ENABLED` 默认关闭。启用时设置 `TENANT_ID`、`CALLER_KEY` 和 `SCOPE_VERSION` 对应的同名前缀配置。调用方键使用现有 `Actor.key()` 约定。
- `COMMERCE_BASE_URL` 和 `COMMERCE_TOKEN` 使用同一前缀。商城凭据从受控配置读取。对端使用 HTTPS，回环隔离可以使用 HTTP。
- `COMMERCE_TENANT`、`MERCHANT_ID`、`STORE_ID` 和 `SKU_ID` 采用已签收配对。配置缺失或格式无效会在启用时启动失败。

数据库迁移为新增表，停用后保留已有审计。完成此接口验收后，后续切片接入带版本的营销签名、冻结应付、原生订单关联及支付前资金预留。
