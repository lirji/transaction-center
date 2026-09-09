# 本地 Casdoor 与演示数据

2026-09-08 已完成本地开通和真实协议/API验收。

## 登录配置

| 项目 | 值 |
|---|---|
| 运营台 | http://127.0.0.1:4180 |
| API | http://127.0.0.1:8400 |
| Casdoor issuer | http://localhost:8000 |
| Casdoor application | `admin/transaction-center-console` |
| organization | `transaction-center` |
| client_id / JWT aud | `transaction-center` |
| 回调 | `http://127.0.0.1:4180/auth/callback`、`http://localhost:4180/auth/callback` |
| scope | JWT 数组 `['trade.admin']`，来自 Casdoor permissionNames |
| tenant_id | JWT 顶层字符串 `900001`，来自 IAM 用户属性 |
| 演示管理员 | `trade-demo-admin` |

随机密码保存在本机 `deploy/.env.casdoor.json` 的 `password` 字段，文件权限0600且Git忽略。其中client secret仅用于IAM侧password-grant冒烟；运营台使用PKCE，不需要或接收client secret。不要把整个凭据文件复制到前端。

当前运营台 `public/config.js` 已对齐上述client_id和organization。只改部署配置，未修改前端业务代码；运行时配置优先于VITE环境变量。若Cursor后续修改默认值，应将 `src/shared/config/runtime.ts` 中的旧回退client_id同步为 `transaction-center`。Casdoor应用名和OAuth client_id是不同字段。

## 可重复执行

从交易中心根目录执行：

```bash
TRADE_IAM_CREDENTIALS="$PWD/deploy/.env.casdoor.json" \
  python3 ../auth-platform/deploy/transaction-center-provision.py
python3 scripts/prepare-demo-database.py
TRADE_ENV_FILE="$PWD/deploy/.env.demo" bash scripts/docker-start.sh --local-build
python3 scripts/verify-casdoor.py
```

前端开发服务器：

```bash
cd frontend/apps/console
npm run dev -- --strictPort
```

现在8400上的API连接 `transaction_center_dev` 并启用 `dev-seed`。原 `transaction_center` 库和 `deploy/.env` 保留；原库账号不复用给演示库。演示库复用dev_infra MySQL84，专用账号是 `trade_demo_app` / `trade_demo_migrator`，配置保存在0600的 `deploy/.env.demo`。

种子通过正常业务服务写入租户900001：演示会员、已发布商品DEMO-P001、SKU DEMO-S001、仓库DEMO-W001、可用库存100件。重启会幂等执行，不重复增加库存。未创建订单、支付或退款假交易。

## 身份属性保护

Casdoor使用独立应用的JWT-Custom配置，不改变rag-shared。scope映射自真实permissionNames；无权限用户即使在OAuth请求中填写trade.admin也不能自行获得此权限。后端严格检查tenant_id是规范正整数文本、可用long表示，且sub非空。

本地Casdoor实际存在个人资料API可修改properties的行为，组织Properties的Admin修改规则不足以阻止这条路径。IAM开通脚本因此先安装 `auth-platform/deploy/transaction-center-tenant-guard.sql`：PostgreSQL触发器仅保护transaction-center组织的tenant_id和owner绑定，禁止既有用户更换租户，包括删除属性或改变JSON类型；其他组织不受影响。用户开通时写入租户；后续租户迁移应重新开通身份，不通过个人资料API修改。该脚本不重启或升级共享Casdoor，不删除既有数据。

IAM还保存三类验收身份（密码在同一0600凭据文件fixtures字段）：无权限reader、900002租户管理员、tenant_id=0的无效身份。它们仅用于 `verify-casdoor.py`，无Casdoor全局管理权限。

## 验证结果与范围

- Casdoor S256 PKCE授权码登录和无client secret换令牌通过，错误verifier被拒绝。
- 管理员读取会员/商品/仓库/库存HTTP200，库存100件。
- reader请求trade.admin不能自授，管理接口HTTP403。
- 900002管理员商品列表为空；尝试修改自身tenant_id后重新登录仍为900002。
- 非法租户、匿名和篡改签名HTTP401。
- JwtValidationTest使用真实RSA/JWKS验证issuer、audience、签名、时间及租户类型/格式/范围、空sub；测试通过。
- 全模块Maven打包成功；API容器healthy；32项Flyway迁移成功；重启后种子不重复。
- 浏览器已验证运营台到Casdoor专属应用的PKCE跳转。完整授权码交换通过协议脚本验证；未宣称已完成浏览器回调后所有页面或业务流程验收，也未执行全量后端回归。

Casdoor自定义声明机制参考[官方JWT实现](https://github.com/casdoor/casdoor/blob/master/object/token_jwt.go)；实际可用性以本地真实签发与上述验收结果为准。
