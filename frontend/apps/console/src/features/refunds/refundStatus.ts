import type { RefundQueueItem } from '../../shared/api/client'

export const refundLabels: Record<RefundQueueItem['status'], string> = {
  PENDING_APPROVAL: '待审批', READY: '待提交', SUBMITTED: '已提交', SUCCEEDED: '退款成功',
  FAILED: '退款失败', REJECTED: '审批拒绝', EXPIRED: '审批过期',
}
