# V1发布机器合同

[asyncapi.json](asyncapi.json)覆盖实际7种发布事件，复用contracts目录既有6组Schema和12个正例。它描述交易中心发送方，不代表营销/对账消费者已经接入。主题为部署前缀加完整eventType，Kafka key为tenantId:aggregateType:aggregateId，应用消息头eventId与正文相同。环境前缀须与TRADE_OUTBOX_TOPIC_PREFIX一致；文档中的localhost:49092仅为dev_infra开发映射。

结构采用[AsyncAPI 3.0.0官方规范](https://www.asyncapi.com/docs/reference/specification/v3.0.0)。为工具兼容性，生成器将现有2020-12 Schema的共同关键词转换为Draft 07并内嵌，原Schema保持不变；未来出现新方言关键词会失败，必须显式评审转换。官方验证Schema与许可快照存于scripts/contracts/vendor，来源及SHA256在sources.json；校验引用只使用本地注册表。

执行：

```sh
./scripts/verify-contracts.sh
# 修改原Schema或事件目录后，重新生成并审查差异：
uv run --with-requirements scripts/contracts/requirements.txt python scripts/contracts/asyncapi_contract.py --write
```

无uv的CI可在隔离Python环境使用`pip install --require-hashes -r scripts/contracts/requirements.txt`预装依赖，再执行相同脚本。根scripts/verify.sh组合Maven验收和机器合同验收；目前未连接远程CI，不能声称流水线已在服务器运行。

检查包括官方结构、所有本地引用、实际发布白名单、生成漂移、正例两种方言一致、消息头/分区键，以及非法操作、破损引用、错误类型、新Schema关键词5个反例门禁。业务跨字段关系（金额守恒、版本一致、资源归属）仍由既有Java EventCodec和正反例测试裁决；JSON Schema本身不能证明这些关系。当前traceId为持久本地关联UUID，traceparent跨服务链路仍待接入。

已验证：7类型、12正例、5个Python门禁全过，独立实际资产审查无阻断。消费者驱动合同、生产ACL/复制/配额与跨平台签署仍保留为未完成。
