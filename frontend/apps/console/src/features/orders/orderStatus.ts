import type { financialStatusSchema, orderStatusSchema } from '../../shared/api/schemas'

export const orderLabels: Record<(typeof orderStatusSchema.options)[number], string> = {
  PENDING_PAYMENT: '待付款', CLOSING: '关单中', CANCELLED: '已取消',
  PAID: '待履约', FULFILLING: '已交付待完成', COMPLETED: '已完成',
}
export const financialLabels: Record<(typeof financialStatusSchema.options)[number], string> = {
  UNPAID: '未付款', NO_PAYMENT_REQUIRED: '无需支付', PAID: '已付款',
  PARTIALLY_REFUNDED: '已部分退款', REFUNDED: '已退款',
}
