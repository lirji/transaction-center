# 配置、前端CI与完整业务联调交付报告

## 结果

T01–T04已完成本轮隔离验收范围：16项配置透传、前端CI门禁、可重放的数据库业务种子、真实Spring HTTP浏览器闭环。分支`feat/console-integration-delivery`，基线49c0c05，未提交/推送；原待办文档改动保留。

| 验收项 | 实现 | 证据 | 状态 |
| --- | --- | --- | --- |
| AC01 配置 | Compose、env模板、归因enabled映射 | 16项解析检查、3项应用边界测试 | 完成 |
| AC02 CI | verify.yml前端任务、两套隔离Playwright配置 | 锁定安装/lint/49单测/build/3合同E2E、YAML解析通过 | 配置完成，远程待触发 |
| AC03 种子 | ConsoleBusinessSeed及ConsoleIntegrationIT | 重放业务号、数据库数量/退款状态、回库余额断言通过 | 完成 |
| AC04 真实联调 | integration.spec.ts，不拦截业务响应 | 6项Chromium用例全部通过 | 隔离范围完成 |

## 变更与验证

部署变更仅新增16项配置透传、Worker敏感入口覆盖和归因开关绑定，无迁移。新增测试身份/种子均位于测试源码，生产控制台页面未修改。CI包含锁定安装、lint、单元测试、构建、浏览器依赖、界面合同测试和真实后端联调，失败阻断并上传分开的报告。

真实联调数据包含会员/商品/库存、3笔订单、2笔支付及尝试、正常和异常实收、待审批/拒绝/成功/已提交4种退款、履约与两笔退货/相关任务。浏览器验证会员冻结解冻、退货If-Match与同键重放；最终库存100=98可用+2预占+0已售，验收及回库没有重复。普通管理员不能读诊断接口、不能批准退款，其他租户不能读取本租户记录。

- 完整后端`TESTCONTAINERS_RYUK_DISABLED=true MAVEN_ARGS=-Dtrade.test.mysql-image=mysql:8.4.11 bash scripts/verify.sh`：454项通过，失败/错误/跳过均0；机器合同9项通过。另行运行的1项Java联调编排测试不计入454。生产jar确认不含测试夹具。
- `python3 scripts/verify-compose-config.py`：PASS，16项默认/覆盖及角色隔离。
- 前端`pnpm install --frozen-lockfile`、lint、test（49项）、build和e2e（3项）：通过。
- `TESTCONTAINERS_RYUK_DISABLED=true bash scripts/verify-console-integration.sh`：Maven SUCCESS；1项Java编排测试包含6项真实浏览器用例（全部通过）和数据库断言。
- 工作流YAML解析及前端12步骤检查通过；本地Node24.12.0，CI选择Node22，尚未获得远程运行结果。
- Review和QA：本轮范围pass，细节见[审查](REVIEW_REPORT.md)、[QA](QA_REPORT.md)。

结构化证据见[verification.json](verification.json)。全量回归首次默认mysql:8.4镜像拉取较慢，停止该次测试后指定本机已有8.4.11重跑通过。

## 运行与后续

执行步骤、配置说明、数据生命周期和报告位置见[联调运行说明](../../CONSOLE_INTEGRATION.md)。状态页、待办、部署说明和前端README已同步；T01–T04勾选仅指上述证据范围。

现有演示4180/8400服务未重建，数据未灌入共享演示库。测试结束释放专属进程和数据库。发布本轮配置前按正常构建/部署流程操作，保留原env；回退可恢复上一版本应用与Compose，无数据库回滚步骤。

远程Actions需提交/推送后触发。真实Casdoor部署、支付/风控供应商、审批和WMS仍依原待办验收；测试签发方与可信资金输入不表示这些平台已接通。自动准入仍默认关闭，启用前必须确认实际数据库磁盘映射。下一可独立推进项为T12隔离多实例验证，尚未自动启动。
