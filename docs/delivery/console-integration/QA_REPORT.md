# QA Report

## 环境

2026-09-09，feat/console-integration-delivery工作区，基线49c0c05。macOS、Java21、Node24.12.0、pnpm9.15.9、Docker Desktop；隔离MySQL8.4.11、V001–V033迁移、随机端口Spring/Vite/OIDC测试身份，Chromium。现有4180/8400前后端保持健康，未重建共享服务。

## 验收用例

| ID | 验证 | 实际证据 | 结论 |
| --- | --- | --- | --- |
| AC01 | 16项Compose默认/覆盖及API/Worker角色 | verify-compose-config.py输出PASS；DeploymentConfigurationTest 3项 | pass |
| AC02 | 锁定安装、lint、49单测、生产构建、3界面合同E2E | 本地命令退出0；工作流YAML解析及12步骤检查通过 | pass |
| AC03 | 二次生产服务种子与落库状态 | ConsoleIntegrationIT业务号相等、3订单/2意图/2尝试/2实收/4退款/1初始退货实收 | pass |
| AC04-1 | PKCE登录、订单支付退款报价异常实收下钻、分页和按订单筛选 | integration.spec.ts用例1 | pass |
| AC04-2 | 真实会员冻结/解冻 | 用例2，列表FROZEN→ACTIVE | pass |
| AC04-3 | 退货验收ETag、同键重放、数据库库存 | 用例3；ETag 0→1、两条验收/回库事实，余额100=98+2+0 | pass |
| AC04-4 | 诊断scope、401/403/429、其他租户空列表/404 | 用例4–6及Java HTTP断言 | pass |

真实联调执行`TESTCONTAINERS_RYUK_DISABLED=true bash scripts/verify-console-integration.sh`，Maven SUCCESS；6项浏览器全部通过（10.8秒），整体32秒。证据路径见[联调运行说明](../../CONSOLE_INTEGRATION.md)。Java外层1项测试包含种子、HTTP、6项浏览器及最终数据库断言，不将其当作6项Java测试重复计数。

## 缺陷与复测

修复测试履约主体配置缺项、严格定位歧义、空响应JSON解析和限流请求发起方式后，完整联调复测通过。不是生产业务逻辑缺陷；没有降低权限断言或使用业务响应拦截来通过测试。

## 外部检查

CI使用Node22，本机为Node24.12.0；未触发远程Actions、未向演示库灌完整种子、未验收真实供应商或审批平台；这些不记为通过。本机禁用Ryuk，JUnit正常退出释放专属容器，现有服务未受影响。

## 结论

本轮pass；最终完整后端回归454项、机器合同9项通过，失败/错误/跳过均0。生产jar排除测试夹具检查通过。结构化结果见verification.json。
