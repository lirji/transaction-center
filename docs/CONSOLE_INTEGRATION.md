# 控制台配置与隔离业务联调

本轮对应待办T01–T04。联调启动真实Spring HTTP、临时MySQL和浏览器，通过生产领域服务写入数据；测试结束销毁隔离库，不向正在运行的4180/8400演示环境灌数据。

需要直接在当前4180查看持久数据，见[演示数据说明](DEMO_DATA.md)；该工具与下面的临时隔离联调独立。

## 配置透传

`deploy/.env.example`与`deploy/compose.yaml`支持以下配置：

| 配置组 | 环境变量 | 默认行为 |
| --- | --- | --- |
| 管理证据 | `TRADE_ADMIN_EVIDENCE_ENABLED` | false，仍须额外scope |
| 管理查询 | `TRADE_ADMIN_QUERY_CONCURRENCY` / `TENANT_CONCURRENCY` / `TENANT_RATE`（后两项同样有`TRADE_ADMIN_QUERY_`前缀） | 4 / 2 / 20 |
| 订单归因 | `TRADE_ORDER_ATTRIBUTION_ENABLED` / `ISSUER` / `JWKS`（同组前缀） | false；启用须配置可信issuer和HTTPS JWKS |
| 自动准入 | `TRADE_AUTOMATIC_ADMISSION_ENABLED` | false |
| 数据库磁盘 | `TRADE_AUTOMATIC_ADMISSION_DISK_PATH` / `DISK_STORE`（同组前缀） | 空，必须明确实际数据库存储映射 |
| 积压高低水位 | `TRADE_AUTOMATIC_ADMISSION_BACKLOG_HIGH` / `BACKLOG_LOW`（同组前缀） | 10000 / 5000 |
| 磁盘水位 | `TRADE_AUTOMATIC_ADMISSION_DISK_MIN_BYTES` / `DISK_RECOVERY_BYTES`（同组前缀） | 5 GiB / 10 GiB |
| 采样与有效期 | `TRADE_AUTOMATIC_ADMISSION_INTERVAL_MILLIS` / `MAX_AGE_MILLIS`（同组前缀） | 15000 / 45000 |

自动准入不默认挂载数据库目录。部署方必须用自己的Compose覆盖文件映射实际数据库所在卷，并核对FileStore标识；远程数据库不能填API容器根盘。未知/失配磁盘应保持关闭或失败关闭，不能伪造健康样本。Worker强制关闭管理证据、订单归因、履约及独立审批等入口；API保持Worker/Outbox循环关闭。

执行`python3 scripts/verify-compose-config.py`可验证16项默认值、覆盖值及角色隔离，不读取演示密钥。应用边界由`DeploymentConfigurationTest`验证。

## 可重放的业务种子与浏览器验证

先准备Docker、Java 21、Maven、Node 22和pnpm 9.15.9，在仓库根执行：

```bash
pnpm --dir frontend/apps/console install --frozen-lockfile
pnpm --dir frontend/apps/console exec playwright install chromium
bash scripts/verify-console-integration.sh
```

Linux缺浏览器系统依赖时先执行`pnpm --dir frontend/apps/console exec playwright install --with-deps chromium`。默认MySQL镜像8.4.11，可通过`TRADE_TEST_MYSQL_IMAGE`指定已认可版本。Testcontainers管理专属容器；本机Docker Desktop若确需禁用Ryuk，可仅在当前命令设置`TESTCONTAINERS_RYUK_DISABLED=true`，强制中断后需核对本次专属容器是否退出。

测试入口为`ConsoleIntegrationIT`，不进入普通单元测试选择集。它执行以下流程：

1. 创建临时库`transaction_center_console_it`，执行V001–V033迁移，独立随机端口启动后端、OIDC和Vite。
2. `ConsoleBusinessSeed`经生产领域服务创建会员、商户、商品、库存、三笔订单（待付/取消/已付并履约）、两笔支付意图/尝试、正常与异常实收、四笔退款（待审批/拒绝/成功/已提交）、已验收与待验收退货及相关任务。
3. 重放相同种子业务键，检查业务号一致及数据库记录数量/状态；禁止SQL强改状态。
4. 浏览器执行OIDC Authorization Code + S256 PKCE登录，真实代理访问后端；检查下钻、分页、会员写入、退货ETag与同键重放、诊断scope、租户隔离、401/403/429。
5. 查询数据库核对退货实收和回库没有重复、库存余额守恒，释放独立进程和容器。

付款观测、审批和退款终态由**测试classpath内的可信输入夹具**提供；生产JWT解码器真实校验RSA签名、issuer和audience。浏览器不拦截业务API。此流程不证明真实Casdoor部署、支付供应商签名、审批平台或渠道网络已经验收；这些仍对应T05–T08。测试身份服务及业务种子均不打包进生产jar，也没有新增HTTP假审批接口。控制台仍无批准退款按钮。

## 证据与CI

- `transaction-server/target/console-integration/manifest.json`：仅业务号，不含JWT。
- 同目录`browser.log`及Surefire报告：种子与真实后端联调结果。
- `frontend/apps/console/playwright-report/integration/`：真实后端浏览器报告；`test-results/integration/`：失败截图。
- `pnpm --dir frontend/apps/console e2e`：单独的3项拦截API界面合同测试，使用4185，不复用已有服务；不当作真实后端证据。

GitHub Actions前端任务执行锁定安装、lint、49项单元测试、构建、上述两类浏览器测试；失败阻断，报告保留7天。真实联调禁用trace/HAR/storageState，避免存储身份令牌。远程运行结果以推送后Actions为准；本地通过不代表远程已运行。
