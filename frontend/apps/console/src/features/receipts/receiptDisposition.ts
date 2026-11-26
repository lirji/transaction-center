import type { receiptDispositionSchema } from '../../shared/api/schemas'

export const dispositionLabels: Record<(typeof receiptDispositionSchema.options)[number], string> = {
  NORMAL: '正常实收', EXCESS: '超额收款', AMOUNT_MISMATCH: '金额不符',
  AFTER_CLOSE: '关单后收款', AFTER_TERMINAL: '终态后收款',
}
