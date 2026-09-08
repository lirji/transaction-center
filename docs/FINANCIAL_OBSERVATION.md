# 资金积压观测运行说明

后台worker每15秒依次采样payment、refund、reconciliation三个域。普通API不启动采样，Prometheus抓取不查询数据库。间隔由TRADE_FINANCIAL_OBSERVATION_INTERVAL_MILLIS控制，范围5～60秒。V030为五张既有表增加覆盖索引，不新增业务表或修改资金事实；394项组合全量通过后，已迁移共享V030并核对五个覆盖索引；47业务表658字段注释完整。

| 指标 | 含义 |
| --- | --- |
| trade.financial.backlog.count.lower.bound | 每状态数量，上限10001，达到上限代表至少该数量 |
| trade.financial.backlog.oldest.age.seconds | 当前样本最老事实距今秒数；空队列0，尚无有效样本NaN |
| trade.financial.observed.at.seconds | 各域最近一次成功采样的主库时间；首次失败为0 |
| trade.financial.observation.failures | 各域累计采样失败次数，不输出异常原文 |

固定状态：支付UNKNOWN、退款资金HELD/SUBMITTED、支付/退款回调PENDING/REVIEW，以及差异OPEN/PROCESSING。UNKNOWN年龄从首次派发许可开始，不证明已发到渠道；退款预占年龄包含审批等待，不能解释为渠道耗时。

域失败保留旧样本，故告警必须同时判断新鲜度，不能把长期未更新的旧数量当作当前值。默认15秒采样可用45秒作为示例新鲜度窗口，自定义间隔同步调整。多个worker看到相同全局数据，聚合取新鲜样本的max，不能sum。计数达到10001时只能报告下界，不能报告精确积压；不跨币种合计资金金额。

无租户、订单、会员、渠道主体标签，不提供外部业务明细接口。采样三域各有三秒只读查询预算；失败不自动释放退款资金或改变UNKNOWN状态，也不自动暂停新订单。自动积压/磁盘准入保护与生产告警阈值/容量验收仍待完成。

验证：实际MySQL查询、九个固定状态、覆盖索引EXPLAIN、10002行积压的10001上限、关闭差异排除、域故障保旧样本和单线程生命周期已验证；原始证据位于任务evidence/financial-observation。初轮唯一中文注释门禁遗漏已修并定向复验，不伪称初轮全绿。
