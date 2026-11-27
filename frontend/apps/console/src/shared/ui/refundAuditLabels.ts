export const refundAuditActions: Record<string, string> = {
  'refund.request': '申请退款',
  'refund.compensation': '异常款项补偿',
  'refund.approved': '批准退款',
  'refund.rejected': '驳回退款',
  'refund.approval.expired': '审批到期',
  'refund.channel.submitted': '提交原渠道',
  'refund.channel.succeeded': '核实退款成功',
  'refund.channel.failed': '核实退款失败',
}

export const refundAuditResults: Record<string, string> = {
  SUCCEEDED: '已提交', REPLAYED: '重复请求', REJECTED: '校验拒绝',
  CONFLICT: '冲突', DENIED: '权限拒绝', UNKNOWN: '待核实',
}
