项目全景文档：[文档导航](docs/README.md) · [项目架构](docs/PROJECT_ARCHITECTURE.md) · [业务说明](docs/BUSINESS_GUIDE.md) · [高并发设计](docs/HIGH_CONCURRENCY_DESIGN.md) · [数据架构](docs/DATA_ARCHITECTURE.md) · [部署运维与容量验证](docs/OPERATIONS_AND_CAPACITY.md)。

# 交易中心 transaction-center

当前交付（2026-09-09，代码基线 `3187c57`）：管理查询接口与控制台已实现，V033已应用到演示库，前后端Docker已在本机启动，五批提交已推送origin/main。最新验证范围、部署边界与剩余工作统一见 [当前交付状态](docs/PROJECT_STATUS.md)。

仓库交付边界见 [会话交接](SESSION_HANDOFF.md)；本机恢复状态另见未入库的 CODEX_PROGRESS.md。已有能力与后续缺口见 [剩余内部工作](docs/TRADE_REMAINING_INTERNAL_WORK.md)。


Java 21／Spring Boot 4.0.2 模块化后端。新项目根目录为 `/Users/liruijun/personal/LLM/transaction-center`，关联平台有独立开发进度；本仓库的提交和验证只覆盖交易中心。

## 当前交付边界

交易内部已具备会员/商品/报价/库存订单、支付与退款恢复、审批拒绝审计、履约/退货、新客与首单事实、V1可靠事件/永久重放、对账、追踪、错误SDK及自动准入。订单新增可选归因令牌、永久冻结快照与单次消费，默认关闭；详见[归因合同](docs/ORDER_REFERRAL_ATTRIBUTION_DESIGN.md)。

真实支付/退款渠道、权威主体及组织店铺映射、隐私方案、原平台完整裂变、生产监控/容量/容灾及外部签收仍未完成。内部测试通过不等于生产验收；当前验证范围与共享迁移状态见交接文档。

模块：

| Maven模块 | 职责 |
|---|---|
| transaction-core | 可信身份、领域校验、事务幂等、Outbox、事务审计 |
| transaction-member | 会员注册／资料／状态、条款与同意历史、不可逆消费事实及首单裁决内部端口 |
| transaction-catalog | 商品草稿、不可变发布快照、库存使用的公开CatalogPort |
| transaction-inventory | 仓库、库存余额与流水、内部预占状态机 |
| transaction-jobs | 数据库租约、持久唤醒、回调／支付恢复／订单关闭编排、默认关闭的有界常驻调度；真实渠道仍待接入 |
| transaction-payment | 渠道登记、支付意图／尝试、可信收款、可靠关闭、默认WAIT的持久风险门禁、退款额度与账本内部Port；尚无真实渠道/风险供应商 |
| transaction-refund | 退款申请、额度、审批/拒绝审计、耐久回调、恢复与异常收款补偿；真实渠道/流程方仍待接入 |
| transaction-order | 商户配置、报价订单、归因永久快照、地址密文、库存组合事务、履约/退货、取消与支付许可屏障 |
| transaction-server | HTTP、安全、Flyway、种子入口、数据库与HTTP测试 |
| transaction-client-sdk | 错误解码与保守重试建议，不执行网络重试 |

## 构建与测试

持久化已统一为MyBatis Mapper接口＋XML，业务服务不再持有JdbcTemplate或内嵌SQL；详见[分层设计](docs/PERSISTENCE_DESIGN.md)。多SKU管理／发布查询和服务端报价已实现，详见[多SKU与报价合同](docs/SKU_AND_QUOTE.md)。订单创建／本人查询／取消屏障及商户配置现已交付，详见[订单基础](docs/ORDER_FOUNDATION.md)。新增[不可逆消费事实内部端口](docs/MEMBER_TRADE_FACTS.md)，支付核心进一步接入订单／库存／消费事实原子编排，见[支付核心合同](docs/PAYMENT_CORE.md)。接口现状以[OpenAPI](docs/openapi.json)为准，真实渠道与完整裂变仍待接通。

```bash
mvn verify
```

需要 Java 21、Maven、Docker。测试使用 `mysql:8.4` 临时隔离容器，与 dev_infra 镜像标签一致；自动回收测试资源，不连接共享数据库、不执行共享实例故障注入。测试容器创建与首次初始化需要时间；没有 Docker 时测试应失败，不静默跳过数据库验收。`mvn -DskipTests package` 仅用于快速打包，不表示验收通过。

## 运营业务编号

V050 增加支付回调、退款回调和退款审批的运营编号。任务列表可用回调接收号查找，退款列表可用审批单号查找，新旧编号均可输入。订单、实收、支付、退款和资金总览优先显示所属单据的业务号；回调验签、历史密文、审批回执和任务关联继续使用原引用。

历史补录使用 `scripts/backfill-business-numbers.py`，默认预览，按目标租户、数据集和游标分批执行。退款审批只补已有审批引用的退款单。

## 管理台前端

运营 SPA 在 `frontend/apps/console`（Vite，`http://127.0.0.1:4180`，`/api` 代理到 `8400`）。登录走 Casdoor，令牌须 `aud=transaction-center`、`scope` 含 `trade.admin`、`tenant_id` 为正整数文本。详见 [frontend/apps/console/README.md](frontend/apps/console/README.md)。

## 开发运行与 dev_infra

默认复用现有 `dev-infra-mysql84-1`；宿主机 `localhost:43306`，Docker external 网络 `dev-infra` 内使用 `infra-mysql84:3306`。交易项目的 Compose 不创建数据库、Redis、Kafka或观测组件。

本机 `transaction_center` 逻辑库已按用户授权创建；新的环境需由基础设施管理员建立该库，并分配仅访问该库的应用账号和迁移账号，应用账号不使用 root。Flyway使用迁移账号建表，运行期应用只需业务表 SELECT/INSERT/UPDATE。生产对不可变流水／事件事实收紧UPDATE权限，按真实访问矩阵验收。该仓库不会自动创建超级用户、重置密码或覆盖别的项目库。

启动需要注入：

| 环境变量 | 含义 |
|---|---|
| TRADE_DB_URL | JDBC URL，默认本机43306的transaction_center；统一UTC连接时区 |
| TRADE_DB_USER / TRADE_DB_PASSWORD | 最小权限运行账号；无默认密码 |
| TRADE_MIGRATION_USER / TRADE_MIGRATION_PASSWORD | 建表迁移账号；本机不配置时回退应用账号，生产必须显式分离 |
| TRADE_JWT_ISSUER | 可信身份签发方；当前不修改其服务配置 |
| TRADE_JWT_JWKS | 可访问的RSA公钥集合地址，必须由可信环境配置而非用户输入 |
| TRADE_JWT_AUDIENCE | 默认transaction-center；签发方令牌须匹配 |
| TRADE_PORT / TRADE_BIND | 默认8400／127.0.0.1；容器配置为0.0.0.0 |

配置完成后可运行打包的 `transaction-server/target/transaction-server-0.1.0-SNAPSHOT.jar`。Docker部署使用以下入口（需Docker Compose、Bash、Python 3）：

```bash
# 首次配置；已有.env时直接编辑，保留原有凭据。
cp -n deploy/.env.example deploy/.env
chmod 600 deploy/.env
# 填写deploy/.env中的数据库账号及JWT配置后：
./scripts/docker-start.sh
```

脚本校验必填配置与外部网络，从源码构建Java 21后端和前端静态页面镜像，启动后等待健康检查通过；不再要求宿主机预先执行Maven打包。首次构建需要下载镜像和Maven依赖。构建跳过测试，完整验收仍使用 `./scripts/verify.sh`。健康检查覆盖应用及数据库可用性，不代表JWT登录、真实渠道或业务闭环已验收。

宿主机已有Java 21、Maven、Node.js和pnpm时，可使用 `./scripts/docker-start.sh --local-build`：脚本先在本机打包后端并安装锁定的前端依赖、构建页面，再构建运行镜像，复用本机依赖缓存；适合首次Maven基础镜像下载缓慢的场景。可与 `--worker` 组合。

镜像健康探针为首次冷启动保留300秒宽限，启动脚本最多等待600秒；服务提前就绪会立即通过，不会固定等待宽限结束。

`TRADE_ENV_FILE=/绝对路径/交易配置.env ./scripts/docker-start.sh` 可指定现有配置；也可直接导出所需环境变量。默认复用 `dev-infra` 网络及 `infra-mysql84`、`infra-redis7`、`infra-kafka38`，可用 `DEV_INFRA_NETWORK` 指定共享网络名。先由dev_infra启动所需服务，脚本不会新建中间件。`TRADE_DB_URL` 可指定共享MySQL中的交易专用逻辑库。JWT公钥地址必须从容器可达，不能用指向宿主机服务的 `localhost`。

默认前端地址为 `http://127.0.0.1:4180`，可用 `TRADE_CONSOLE_HOST_PORT` 调整端口。Nginx提供静态页面、登录回调路由和 `/api/` 同源后端代理；运行时公开配置位于前端 `public/config.js`。演示环境使用 `TRADE_ENV_FILE="$PWD/deploy/.env.demo" ./scripts/docker-start.sh --local-build` 启动前后端。

默认API地址为 `http://127.0.0.1:8400`，健康检查为 `/actuator/health`；可用 `TRADE_HOST_PORT` 调整宿主端口。运行 `docker compose -f deploy/compose.yaml logs --tail 100 trade-api` 查看日志，运行 `docker compose -f deploy/compose.yaml stop trade-api` 停止API。使用自定义配置文件时，这些命令也需加 `--env-file /绝对路径/交易配置.env`。

需要后台任务时显式运行 `./scripts/docker-start.sh --worker`；省略该参数不会停止此前已运行的worker，需要时执行 `docker compose -f deploy/compose.yaml --profile worker stop trade-worker`。生产必须使用HTTPS网关及受控内部网络；当前Compose用于本机开发部署。

后台处理默认关闭，Compose的`trade-api`固定禁用。`trade-worker`仅在显式`worker` profile启用时启动，复用dev_infra，不发布宿主端口。先确认迁移、凭据和真实渠道准入；配置、停机语义及未完成的监控／容量门禁见[常驻Worker合同](docs/WORKER_RUNTIME_PLAN.md)。本轮未部署或启用共享后台，不得据此开展真实收款。

JWT必须有合法签名、iss、aud、有效时间、sub和正整数文本 `tenant_id`。后台还需 `scope` 包含 `trade.admin`。不存在无签名调试令牌、任意租户请求头或默认管理员密码。测试里的模拟身份和本地JWKS仅在 `src/test`。

Redis 缓存与 Kafka V1 事件已有开发环境专项证据；生产配置、外部消费者签收及完整观测接入仍待完成。旧内部 v0 事件与正式 V1 合同需区分，不能以开发发布通过替代生产验收。

## 开发种子

显式建立独立逻辑库 `transaction_center_dev`，把 TRADE_DB_URL 指向它，在应用启动参数中启用 `--spring.profiles.active=dev-seed`。`DevSeed` 通过正常业务服务写入固定演示租户900001的会员、商品、仓库和100件库存。重复执行不重复加库存，其他数据库名称直接拒绝。数据存储于MySQL，前端必须通过API获取；种子身份不是可登录的默认账号。不能在生产启用该profile。

## 文档与后续交接

- [当前API机器合同](docs/openapi.json)
- [实现决策与限制](docs/IMPLEMENTATION.md)
- [Mapper分层设计与验收](docs/PERSISTENCE_DESIGN.md)
- [多SKU与报价](docs/SKU_AND_QUOTE.md)
- [订单与库存事务](docs/ORDER_FOUNDATION.md)
- [退款资金内部合同](docs/REFUND_FUNDS_CONTRACT.md)：预占及资金账本前置，不是完整退款API
- [正常退款申请及内部执行](docs/REFUND_APPLICATION.md)：申请/查询、后台政策及同事务组件资金状态
- [退款渠道与恢复](docs/REFUND_CHANNEL_RECOVERY.md)：原路策略、UNKNOWN与证据交接；主动恢复待继续
- [退款回调Inbox](docs/REFUND_CALLBACK_INBOX.md)：V015持久密文/租约消费及第四通道已通过207项全量验证；真实渠道仍未接入
- [退款后续实施](docs/REFUND_IMPLEMENTATION_PLAN.md)：明细、审批、原路执行、退货与营销追回
- [裂变完整交付依赖](docs/delivery/REFERRAL_ROADMAP.md)
- [Cursor前端交接](docs/CURSOR_HANDOFF.md)
- [会员生命周期与查询能力说明](docs/MEMBER_LIFECYCLE.md)
- [分支交付与验证记录](docs/GIT_DELIVERY.md)
- 完整生产设计仍在 `../marketing-lowcode-platform/docs/plans/transaction-center-20260907/`，其中所有拟定API不是都已实现；以本项目机器合同与代码为首批真实能力。

任何新增表和字段必须中文COMMENT；生产类及公共方法需有中文Javadoc，关键分支说明业务原因。数据库注释与Java语法树注释检查已纳入测试，覆盖度不替代对注释内容的人工复核。


近期后端切片：参见[退款审批HTTP与拒绝审计](docs/REFUND_APPROVAL_HTTP.md)、[异常收款唯一补偿](docs/REFUND_COMPENSATION.md)、[实际交付与商业完成](docs/ORDER_FULFILLMENT.md)及[退货唯一回库接续设计](docs/RETURN_RECEIPT_DESIGN.md)。当前验证、演示库迁移版本和未完成范围以[当前交付状态](docs/PROJECT_STATUS.md)为准；[会话交接](SESSION_HANDOFF.md)保留历史过程；内部能力不等于真实渠道或完整裂变生产验收。

## 持续集成

[GitHub Actions](.github/workflows/verify.yml)复用scripts/verify.sh，Java21、隔离MySQL及锁定的Python合同依赖，失败也保存测试报告。配置已本地解析，底层Maven/合同命令分别验证；远程执行、安全扫描及生产发布签收仍待完成，无部署步骤。

### 本地统一登录与演示数据

Casdoor交易应用已按 `aud=transaction-center`、`scope` 含 `trade.admin`、顶层字符串 `tenant_id=900001` 对齐。开通/数据库准备/真实PKCE与API验收脚本及账号位置见 [本地Casdoor接入说明](docs/CASDOOR_LOCAL_SETUP.md)。演示API使用独立 `transaction_center_dev` 库及 `deploy/.env.demo`，不会向原业务库写演示种子。
