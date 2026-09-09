# 部署运维与容量验证

更新日期：2026-09-09。运行说明依据代码基线3187c57。前后端本机部署与演示库V033迁移已完成；验证范围见 [当前交付状态](PROJECT_STATUS.md)。本次仅同步文档，未重新部署、迁移或执行压测。返回 [文档导航](README.md)。

## 1. 本地运行拓扑与前置条件

使用 Docker Compose、Bash 和 Python 3；选择本机构建时另需 Java 21、Maven、Node.js和pnpm。公共中间件复用 dev_infra：MySQL 8.4（容器 infra-mysql84:3306，宿主 43306）、Redis 7（infra-redis7:6379，宿主 46379）、Kafka 3.8（infra-kafka38:9092，宿主 49092）。实际地址以目标环境配置为准。

项目 [Compose](../deploy/compose.yaml) 不启动这些公共组件。运行账号与迁移账号仅访问交易专用库，凭据放在 Git 忽略的配置或外部 Secret 中；不要打印展开后的含密码 Compose 配置。

| 角色 | 启动方式 | 运行责任 |
| --- | --- | --- |
| API | 默认 trade-api | HTTP 服务；固定关闭后台领取、Outbox 发布/维护 |
| Worker | 显式 worker profile | 八类恢复/发布通道；无宿主端口映射 |
| 运营台 | 默认trade-console，源码frontend/apps/console | Nginx静态页面与/api/代理，宿主4180；Casdoor登录和交易运营 |
| 演示种子 | dev-seed profile | 仅 transaction_center_dev 专用库，正常业务服务写种子 |

当前本机部署的演示 API 使用 `deploy/.env.demo` 与 `transaction_center_dev`。重新运行前核对配置文件及目标库，不能根据默认值误切回其他库。账号位置和开通步骤见 [Casdoor 接入](CASDOOR_LOCAL_SETUP.md)；这里不复制任何凭据。

## 2. 运行入口与检查

仓库根 [README](../README.md) 提供首次配置步骤。[docker-start.sh](../scripts/docker-start.sh) 从源码构建镜像并等待健康；本机Java/Maven和Node.js/pnpm可用时使用 `--local-build`，需要 Worker 时加 `--worker`。以下为已使用的启动入口；本次文档同步未重复执行：

```bash
# 本机已有演示配置时，用明确的配置文件构建并启动前后端。
TRADE_ENV_FILE=deploy/.env.demo ./scripts/docker-start.sh --local-build

# 基础存活/数据库健康检查。
curl -fsS http://127.0.0.1:8400/actuator/health
```

首次镜像/依赖下载可能需要网络。`--local-build` 的 Maven 打包跳过测试，只用于构建；健康为 UP 也不证明 JWT、渠道资金或业务闭环验收通过。探针冷启动宽限 300 秒，脚本最多等待 600 秒，提前就绪会提前返回。

后台开关不能因任务积压就未经配置直接全部打开。启用前核对对应迁移、密文密钥、渠道适配和来源授权。省略 `--worker` 不会停止此前已运行的 Worker。停机与日志命令见根 README，使用自定义配置时需保持相同 env-file。

当前本机只部署trade-api与trade-console，未启用trade-worker。后台默认关闭时 READY 任务不会自行消失；运行通道关闭与任务为空是不同状态。Worker 默认收尾预算 30 秒，Compose stop_grace_period 为 60 秒；失联工作由租约到期后接管，不靠人工重置 RUNNING。

## 3. 配置与资源预算

| 配置 | 代码/配置初值 | 含义与范围 |
| --- | --- | --- |
| Hikari maximum-pool-size | 10 | 每个应用进程的数据库连接上限 |
| Hikari connection-timeout | 3000ms | 获取连接等待预算 |
| MyBatis default-statement-timeout | 10s | 单语句超时；不是所有 HTTP 的端到端超时 |
| TRADE_ORDER_TTL_SECONDS | 900 | 支付截止检查；不代表到点可无条件释放 |
| TRADE_CATALOG_CACHE_ENABLED | false | 展示 Redis 缓存 |
| TRADE_CATALOG_QUERY_DB_CONCURRENCY / RATE | 2 / 64 | 每实例回源并发/每秒令牌补充速率 |
| TRADE_CATALOG_QUERY_MAX_KEYS / WAIT_MS | 1024 / 150 | 在途查询键上限/单飞等待预算 |
| TRADE_HOT_SKU_PERMITS / MAX_KEYS | 32 / 4096 | 每实例热点预占与跟踪键预算 |
| TRADE_WORKER_ENABLED | false | 常驻后台运行；Compose Worker 显式 true |
| 回调线程/其他业务通道 | 支付回调 2，其余各 1 | 实际发布/对账查询关闭时对应通道 0；总上限 16 |
| TRADE_OUTBOX_ENABLED | false | 是否向 Kafka 发送；关闭仍保留事件任务 |
| TRADE_OUTBOX_MAINTENANCE_ENABLED | false | 周期历史发布补漏 |
| TRADE_FULFILLMENT_ENABLED | false | 履约写入与会员确认，另需来源权限 |
| TRADE_AUTOMATIC_ADMISSION_ENABLED | false | 积压与实际数据库磁盘自动保护 |

值来自 [application.yml](../transaction-server/src/main/resources/application.yml)、[Compose](../deploy/compose.yaml)、各配置类。Spring 支持的配置不一定已经由 Compose environment 透传：当前 Compose 没有显式透传自动准入系列、订单归因 enabled、TRADE_ADMIN_EVIDENCE_ENABLED及TRADE_ADMIN_QUERY_*管理查询限额，不能只在 `.env` 加一个名字就假定容器已启用；部署时需检查最终有效配置并按授权补齐部署配置。此文档任务不改 Compose。

连接预算需要累加：若 API 数量为 A、Worker 数量为 W，各池仍为 10，则应用连接上限约为 `10 × (A + W)`，另留迁移、观测、运维及其他服务连接余量。此式不是吞吐公式。Worker 线程数也不等于数据库连接数，但增加线程会提高竞争，必须联动观察。

## 4. 身份、密钥与观测

访问令牌需合法签名、iss/aud/有效期、非空 sub、规范正整数字符串 tenant_id；管理员 scope 含 trade.admin。证据、对账、履约和审批各有独立开关/权限。监控 `/actuator/prometheus` 需要 `trade.metrics.read`，普通管理员不自动拥有抓取权限。

地址与回调加密配置为空时相关能力拒绝执行，不生成默认密钥。历史密文需要保留原密钥版本；外部 KMS 和密钥轮换流程需有明确验收。新环境不能把本地专用 Casdoor 数据库保护配置推断为已具备生产统一身份治理。

| 观测范围 | 代码已有观测点 | 运维解释 |
| --- | --- | --- |
| Worker | polls、handled、errors、elapsed、active、running | handled 不等于资金成功数 |
| 商品保护 | 缓存命中、负命中、回源、单飞、拒绝、热点跟踪 | 关注故障时回源是否仍有界 |
| Outbox | READY/RUNNING/REVIEW 数量下界、最老年龄、采样时间/失败 | 每状态截断到 10001，不能当精确总量 |
| 财务 | 支付/退款/对账积压与样本新鲜度 | 某域失败不应被其他域成功掩盖 |
| 自动准入 | trade_automatic_admission_blocked | 自动暂停不代表人工策略已恢复 |
| 基础设施 | 需接入 DB 连接/锁/IO/复制、Kafka、主机指标 | 仅有应用指标不足以判断生产健康 |

指标抓取读取缓存采样结果，不为每次 Prometheus 请求扫业务表。同一数据库多个 Worker 看到同一全局队列，应按数据库部署维度聚合并取适当 max，不能 sum 重复计算；同时检查每实例样本新鲜度和缺失。

[monitoring](../deploy/monitoring/) 中已有规则文件，文件存在不等于已加载到 Prometheus/Alertmanager，也不等于已经通知人员。追踪通过 traceId/traceparent 关联请求、任务与事件；日志避免原始主体、回调正文、密钥和任意异常正文。

## 5. 常见故障处理路径

| 现象 | 先核对 | 允许的恢复方向 |
| --- | --- | --- |
| 商品请求 429 增多 | 热键比例、单飞/回源拒绝、Redis 延迟、池等待 | 调整经压测的预算/缓存配置；调用方退避 |
| 新订单/支付 503 | 人工策略、自动样本是否过期、积压/磁盘映射 | 原因恢复后按有效策略恢复；已有业务继续 |
| 支付 UNKNOWN / CLOSING 长时间不动 | 原渠道号、恢复通道、租约、可信关闭证据 | 查原号并保留资金/库存，不按本地超时强改失败 |
| 退款 SUBMITTED 长期未知 | 原退款号、回调密钥版本、恢复通道、渠道结果 | 查询与耐久消费，保持预占；禁止重复新号退款 |
| Worker RUNNING 残留 | owner/token/lease_until、进程存活、DB 时间 | 让到期接管协议恢复，不手改 token/attempts |
| Outbox REVIEW | 合同、发送错误分类、Kafka ACK、事件版本 | 修复原因后走受控重放接口，保留 eventId/payload |
| Outbox 采样缺失/过期 | Worker 生命周期、数据库、抓取 JWT | 修复采样/认证；缺数据不能解释为空队列 |
| 对账 RESULT_ACCEPTED | Inbox 后续结果、差异案例当前状态 | 完成外部核对；不能视为自动结案 |

受控 Outbox 重放需要 trade.admin 与 trade.events.replay、版本、幂等键、原因和工单引用；工单引用只是审计字段，不表示系统已创建外部工单。维护扫描只为 PENDING 白名单 V1 补任务，不重启 DONE/REVIEW，也不删除历史事实。

滚动升级后补漏要考虑旧写入进程及在途事务。固定 ID 上界不是提交水位，周期扫描需从零开展新轮次以覆盖迟提交低 ID，不能只维护一个无限递增游标。

## 6. 测试分层与现有入口

| 层级 | 入口/资产 | 可以说明 | 不能说明 |
| --- | --- | --- | --- |
| 编译打包 | Maven package，部署脚本跳过测试路径 | 当前依赖及代码能构建 | 正确性/容量通过 |
| 后端回归 | `mvn verify` | 默认测试集与隔离 MySQL 功能验证 | 真实渠道、Redis/Kafka 显式 IT、容量 |
| 统一验收 | [scripts/verify.sh](../scripts/verify.sh) | Docker 前置、Maven 回归及 Python 合同检查 | 生产发布已完成 |
| Redis/Kafka 专项 | RedisCatalogCacheIT、KafkaPublisherIT | 指定中间件的显式集成 | 大规模故障/消费者业务签收 |
| 身份本地验收 | [verify-casdoor.py](../scripts/verify-casdoor.py) | 专用演示环境协议与权限 | 生产 IAM 或所有页面验收 |
| 容量/故障 | 下述矩阵，待执行 | 指定负载和环境的容量与恢复证据 | 对任意硬件/数据规模的通用承诺 |

统一验收需要 Docker，数据库测试使用临时隔离 MySQL 8.4；没有 Docker 应失败，不静默跳过。Redis/Kafka IT 需显式执行并隔离测试键/主题，详细命令见相应专题。本次文档整理没有运行这些集成或回归命令。

## 7. 容量验证方案（待执行）

### 7.1 固定实验条件

先记录代码提交及工作区差异、Java/容器资源、A/W 副本数、每通道线程、连接池、MySQL/Redis/Kafka 配置、网络拓扑、磁盘和测试工具版本。容量数据至少包含租户/会员/商品/SKU/仓库/订单/流水/任务数量及索引分布；通过种子脚本或业务 API 写入隔离库。

区分“并发用户数”“施加 RPS”“成功业务 RPS”“包含限流的响应 RPS”。负载必须同时覆盖同 SKU/同会员/同商户与分散场景；每笔新订单使用独立报价和合法请求，同键重放作为单独场景，否则会把重复缓存结果误当订单创建吞吐。

本地单机 dev_infra 结果只代表开发环境。真实渠道先用明确合同的隔离模拟/沙箱验证延迟和不确定结果，生产渠道及共享实例故障注入需单独授权。

### 7.2 压测矩阵

| 场景 | 负载变化 | 正确性断言 | 主要观测 |
| --- | --- | --- | --- |
| 商品列表/详情 | 热缓存、冷缓存、随机不存在键 | 租户/发布代次隔离，价格最终主库裁决 | P95/P99、命中率、回源与拒绝 |
| 展示缓存故障 | 隔离 Redis 不可用/超时 | DB 回源仍受预算，不无限扩散 | 池等待、超时、429/503、内存 |
| 分散正常下单 | 逐级增 RPS/并发，多 SKU/多租户 | 一报价一单，所有预占/事件共同提交 | 成功创建 RPS、事务时长、死锁 |
| 单热点 SKU | 集中不同请求争抢有限库存 | 无负库存、成功预占总量不超库存 | 热点拒绝、行锁等待、公平性 |
| 重复命令 | 同键同摘要/异摘要竞争 | 同摘要同结果，异摘要冲突 | 重放时延、唯一键等待 |
| 支付与取消 | 成功/关闭/迟到回调交错 | 不丢资金、不重复确认、不提前释放 | UNKNOWN/CLOSING 年龄、恢复延迟 |
| 退款竞争 | 多笔申请累计到额度边界 | 成功+在途不超实收/组件；未知保留额度 | hold 等待、重复源号、恢复积压 |
| 并发退货 | 同原预占/双向跨仓/重复验收 | 累计实收与回库预算、双仓守恒 | 余额锁、重复流水、死锁 |
| Worker 故障 | 领取后退出、租约过期、旧进程返回 | 陈旧提交回滚，新 token 接管 | 接管时间、重复远程观察 |
| Kafka 故障 | 发送失败、ACK 后本地失败 | 事件不丢，重复 eventId 不重复消费 | 积压增长、恢复排空速率 |
| 自动准入 | 高低阈值、过期样本、磁盘信号失效 | 暂停新增，恢复通道仍可推进 | 开关滞回、有效样本年龄 |
| 多副本与租户 | API/Worker 副本变化、冷热租户混合 | 无跨租户干扰/饥饿，预算可控 | DB 连接总数、各租户完成时延 |

### 7.3 阶梯与验收方式

建议先做低流量正确性基线，再阶梯增加负载（例如目标的 25%、50%、75%、100%），每阶梯明确预热与稳态窗口；稳态后做突发、故障和恢复，最后执行较长持续负载观察资源增长。具体持续时间、数据规模和通过阈值需写进实验计划，不能把本段示例当已批准生产 SLO。

吞吐验收同时报告成功业务、预期拒绝和异常失败，P99 明确计算口径；不能大量拒绝后仍声称订单 RPS 达标。零超卖、零重复资金记账、零超额退款和跨租户隔离属于不变量；发现任何违反即停止该轮验收并保留证据。

稳态队列需满足消费速度能覆盖生成速度；若故障积压为 B、恢复消费速率为 μ、新增速率为 λ，则仅当 μ > λ 时，理想排空时间约为 `B / (μ - λ)`。这是容量估算，实际还受重试退避、租户轮转和渠道限额影响。

连接占用可先用 `并发占用 ≈ 成功 TPS × 平均持连接秒数` 估算，再用实测分布检查尾延迟；不要用 HTTP 平均耗时替代事务持连接时间。磁盘预算需包含业务表/索引、Outbox/Inbox、日志及数据库自身开销，不能仅按消息 payload 平均大小估算。

### 7.4 每轮报告必须保留

环境与版本、种子规模/分布、负载脚本及参数、预热/稳态窗口、成功/拒绝/错误数、P50/P95/P99、连接/锁/CPU/IO/内存、任务年龄与排空时间、SQL 执行计划、业务不变量核对、故障注入及恢复时间线。结论只适用于该次配置，不复用历史测试数量冒充当前全量结果。

## 8. 生产完成条件

待验证范围包括真实渠道与来源身份、Kafka 生产安全/复制及消费者合同、数据库存储映射、备份恢复演练、应用与数据库容量、可用区故障、告警通知、业务与前端闭环。当前代码具备多项内部机制，但这些环境结果需单独形成证据后才能宣称生产就绪。

## 9. 历史文档整理验证记录（2026-09-08）

下述数量只代表当时的源码快照；当前OpenAPI为69路径/81操作，迁移文件为33份。最新测试和部署证据见 [当前交付状态](PROJECT_STATUS.md)。

2026-09-08 完成静态校验：六份新文档的 111 个本地链接均存在；10 个模块及其直接内部 Maven 依赖与 POM 一致；32 份迁移中的 48 张业务建表全部纳入数据目录；OpenAPI 文件统计为 55 路径/66 操作。七个 Mermaid 代码块已检查围栏完整性，未执行图形渲染验证。

已对照 Commands、库存 Mapper、热点准入、商品查询/Redis、租约 Mapper/Service、Worker 装配、履约入口及配置类核对关键描述，并给九份历史专题增加时效说明。`git diff --check` 通过；未运行 Java 回归、数据库/中间件集成、服务部署或压力测试，历史测试结果不计作本次结果。
