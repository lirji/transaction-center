# 错误码 SDK

依据原外部合同第1节：错误体包含 code/message/traceId/retryable；未知支付不等于失败；可重试必须沿用原幂等键。提供独立 Java 21 模块 transaction-client-sdk，无 Spring、数据库、渠道或网络运行依赖，供现有 Java 平台使用。前端接入仍由 Cursor 负责。

SDK 保留字符串原始 code，兼容未来服务端新增错误，不以 enum 反序列化拒绝未知码；traceId 为不透明关联号，保留退款审批 ARJ 审计号。SDK 不发送请求、不生成新幂等键，不把重试建议当作交易执行。

RetryDecision 输入 HTTP 状态、错误体、原请求是否携带幂等键及 Retry-After 秒数：支付未决优先查询原交易；412/428/版本冲突先读取最新版本；幂等冲突禁止更换键重发；只有429/503且服务端明确 retryable=true 并保留原键时才建议延时重试。其他错误停止自动重试；未知错误码保留且默认人工处理。传输超时无响应时明确建议查询原交易，不能推断失败。Retry-After 为非负秒数，仅无头时默认1秒，非法或无法理解的值停止自动重试，超过300秒停止自动重试，不缩短服务端要求的等待；不承诺已支持HTTP-date形式。

稳定错误常量来自当前业务拒绝及统一响应码，机器清单同源生成。构建检查服务端新增错误是否遗漏 SDK。消息仅供展示，不能靠中文 message 判断程序分支；不能记录包含敏感字段的请求体。

验证覆盖：未知码前向兼容、ARJ原样保留、拒绝错误HTTP状态/可重试标志组合、无原键不能重试、未知支付优先查询、版本刷新、原始超时、Retry-After边界。现有平台真实消费合同及包仓库发布未完成，不以本地单测替代。

## Java 调用方接入

依赖 `com.lrj.trade:transaction-client-sdk:0.1.0-SNAPSHOT`，用调用方现有 JSON 库将标准错误体解码为 `TradeFailure`，再调用 `RetryDecision.from(status, failure, originalKey != null, retryAfter)`。记录 `failure.code()` 与不透明 `traceId`，不要记录敏感请求或按 message 匹配。`RETRY_SAME_KEY` 必须复用同一个请求内容与原键；`QUERY_ORIGINAL` 查询已知原订单/支付资源；`REFRESH_VERSION` 读取最新对象后让业务方重新判断更新意图；`STOP` 交给业务处理。

SDK 不依赖服务端实现包。构建 `mvn -pl transaction-client-sdk package` 生成独立 JAR。`scripts/verify-contracts.sh` 同时检查源码发出的错误码、SDK常量与机器清单，新增错误需更新清单与常量，并显式审查能否进入重试白名单。该检查对直接业务异常及统一响应字面量生效；引入动态错误码工厂时须同步扩展检查，不声称简单静态扫描可证明任意代码的全部行为。
