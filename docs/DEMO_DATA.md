# 本机可浏览的交易测试数据

2026-09-09已写入当前`dev_infra`的`transaction_center_dev`库，租户900001。打开[控制台](http://127.0.0.1:4180)，使用现有交易管理员登录即可查看；会员、商品和仓库中的“联调”名称表示新增测试数据。旧“演示”基础数据保留。

| 页面 | 新增数据 |
| --- | --- |
| [订单](http://127.0.0.1:4180/orders) | 待支付、已取消、已支付并完成履约，共3笔 |
| [支付](http://127.0.0.1:4180/payments) | 待处理和支付成功，共2笔支付意图及尝试 |
| [实收](http://127.0.0.1:4180/receipts) | 正常实收200元、异常额外实收1元，各1笔 |
| [退款](http://127.0.0.1:4180/refunds) | 待审批、拒绝、成功、已提交等待渠道结果，各1笔，每笔10元；查看全部状态可见完整4笔 |
| [退货](http://127.0.0.1:4180/returns) | 待验收、已验收并回库，各1笔 |
| 会员/商品/库存 | 新增联调会员、100元商品、联调仓库；初始100件库存，经交易及一次回库后97可用+2预占+1已售 |

相关报价、订单冻结明细、履约、资金/库存流水和任务均随业务服务写入。支付、审批、退款终态由明确的测试输入生成，不代表真实供应商已收付资金。现有后台派发和审批/履约入口权限保持原配置，不能因为有测试数据就绕过权限操作。

## 重复执行和验证

前置：当前演示数据库、API及Casdoor运行，Java21/Maven/Python3可用。仓库根执行：

```bash
python3 scripts/seed-demo-business.py
python3 scripts/verify-demo-business.py
```

种子工具只支持固定本机开发库，通过测试classpath中的生产领域服务写入，不执行SQL强改资金终态。第一次执行会为实物订单生成地址加密密钥，保存在忽略的`deploy/.env.demo`；若运行实例尚未加载这份密钥，需要执行以下命令（本轮已执行）：

```bash
docker compose --project-directory deploy -f deploy/compose.yaml --env-file deploy/.env.demo up -d --no-build --no-deps --wait trade-api
```

业务号在`deploy/demo-business-manifest.json`，本机生成、不提交Git。已有清单时工具核对目标订单存在并跳过创建，保留你冻结会员、修改库存等操作，不重置状态；不要删除清单来尝试重置数据库。密钥与业务库一起保留，不要任意轮换或丢弃。需要全新自动化场景，使用[隔离联调](CONSOLE_INTEGRATION.md)，它不会依赖人工操作后的演示数据。

验证脚本使用真实Casdoor PKCE和4180代理，只读检查13条详情及4笔退款，不打印JWT或密码。种子日志在`transaction-server/target/demo-business/seed.log`；本轮首次创建和第二次执行均通过，13条详情均HTTP200，API与控制台健康。
