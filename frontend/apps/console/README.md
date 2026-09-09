# 交易中心管理台

交付状态（2026-09-09）：控制台已推送main，并以trade-console容器在本机4180提供服务；订单、支付/尝试、实收、报价、退款详情、退货列表及按权限显示的证据/资格/任务查询已接入。最新验证与待办见 [项目状态](../../../docs/PROJECT_STATUS.md)，接口见 [管理查询合同](../../../docs/ADMIN_QUERY_API.md)。默认启动方式及演示配置见 [根README](../../../README.md)。


独立 SPA，调用本仓库 `docs/openapi.json` 已交付的 `/api/v1/trade/admin/**` 接口。不是会员商城，也不并入营销中枢。

```bash
cd frontend/apps/console
pnpm install
pnpm dev          # http://127.0.0.1:4180 ，/api 代理到 127.0.0.1:8400
pnpm test
pnpm e2e
```

登录走 Casdoor（默认 `http://localhost:8000`）。令牌必须：

- `aud=transaction-center`
- `scope` 含 `trade.admin`
- `tenant_id` 为正整数文本；读演示种子需匹配 `900001`

Casdoor 应用若尚未签发上述 audience / scope，需要 IAM 侧补齐，前端不会自制 JWT 或加租户请求头。

`public/config.js` 可覆盖 `OIDC_CLIENT_ID` / `OIDC_ORGANIZATION`。Outbox 重放、对账只查单、改准入策略还需要对应额外 scope。
