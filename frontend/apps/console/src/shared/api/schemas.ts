import { z } from 'zod'

export const idString = z.string().regex(/^[1-9][0-9]{0,18}$/)
export const memberIdString = z.string().regex(/^[1-9][0-9]*$/)
export const currencyCode = z.string().regex(/^[A-Z]{3}$/)
export const isoDateTime = z.string().min(1)

export const failureSchema = z.object({
  code: z.string(),
  message: z.string(),
  traceId: z.string(),
  retryable: z.boolean(),
})
export type Failure = z.infer<typeof failureSchema>

export const resourceSchema = z.object({
  resourceId: z.string().max(64),
})
export type Resource = z.infer<typeof resourceSchema>

export const memberSchema = z.object({
  memberId: memberIdString,
  displayName: z.string(),
  status: z.string(),
  registeredAt: isoDateTime,
  everPaid: z.boolean(),
  version: z.number().int(),
})
export type Member = z.infer<typeof memberSchema>

export const consentDocumentSchema = z.object({
  id: memberIdString,
  purpose: z.string(),
  documentVersion: z.string(),
  title: z.string(),
  contentUrl: z.string(),
  contentHash: z.string(),
})
export type ConsentDocument = z.infer<typeof consentDocumentSchema>

export const merchantSchema = z.object({
  merchantId: z.string(),
  merchantNo: z.string(),
  name: z.string(),
  status: z.enum(['ACTIVE', 'FROZEN']),
  version: z.number().int().min(0),
})
export type Merchant = z.infer<typeof merchantSchema>

export const productStatusSchema = z.enum(['DRAFT', 'PUBLISHED', 'OFFLINE'])
export const productSchema = z.object({
  productId: memberIdString,
  skuId: memberIdString,
  name: z.string(),
  status: productStatusSchema,
  version: z.number().int(),
  unitPrice: z.number().int(),
  currency: z.string(),
  publishedRevision: z.number().int().nullable(),
})
export type Product = z.infer<typeof productSchema>
export const adminProductSchema = productSchema.extend({ productNo: z.string() })

export const stockTypeSchema = z.enum(['PHYSICAL', 'SERVICE'])
export const specificationSchema = z.record(z.string(), z.string())
export const skuSchema = z.object({
  skuId: idString,
  skuNo: z.string().min(1).max(64),
  productId: idString,
  title: z.string().min(1).max(200),
  unitPrice: z.number().int().min(0),
  currency: z.string(),
  stockType: stockTypeSchema,
  specification: specificationSchema,
  revision: z.number().int().min(0),
})
export type Sku = z.infer<typeof skuSchema>

export const warehouseSchema = z.object({
  id: memberIdString,
  warehouseNo: z.string(),
  name: z.string(),
  version: z.number().int(),
})
export type Warehouse = z.infer<typeof warehouseSchema>

export const balanceSchema = z.object({
  id: memberIdString,
  warehouseId: memberIdString,
  skuId: memberIdString,
  stockTotal: z.number().int(),
  available: z.number().int(),
  reserved: z.number().int(),
  sold: z.number().int(),
  version: z.number().int(),
})
export type Balance = z.infer<typeof balanceSchema>

export const ledgerEntrySchema = z.object({
  id: memberIdString,
  operationRef: z.string(),
  warehouseId: memberIdString,
  skuId: memberIdString,
  operationType: z.string(),
  availableDelta: z.number().int(),
  reservedDelta: z.number().int(),
  soldDelta: z.number().int(),
  totalDelta: z.number().int(),
  reason: z.string(),
  occurredAt: isoDateTime,
})
export type LedgerEntry = z.infer<typeof ledgerEntrySchema>

export const refundPolicySchema = z.object({
  currency: z.string(),
  autoApproveLimit: z.number().int().min(0),
  version: z.number().int().min(0),
})
export type RefundPolicy = z.infer<typeof refundPolicySchema>

export const refundStatusSchema = z.enum([
  'PENDING_APPROVAL', 'READY', 'SUBMITTED', 'SUCCEEDED', 'FAILED', 'REJECTED', 'EXPIRED',
])
export const refundQueueItemSchema = z.object({
  cursor: memberIdString,
  refundNo: z.string(),
  businessNo: z.string().nullish(),
  orderNo: z.string(),
  amount: z.number().int(),
  currency: z.string(),
  status: refundStatusSchema,
  approvalRef: z.string().nullable(),
  approvalDeadline: isoDateTime.nullable(),
  version: z.number().int(),
})
export type RefundQueueItem = z.infer<typeof refundQueueItemSchema>

export const channelViewSchema = z.object({
  channelId: idString,
  merchantNo: z.string().min(1).max(64),
  channelCode: z.string().min(1).max(64),
  channelMerchantNo: z.string().min(1).max(255),
  currency: z.string().min(1).max(3),
  returnUrlId: z.string().min(1).max(64),
  status: z.enum(['ACTIVE', 'FROZEN']),
  version: z.number().int().min(0),
})
export type ChannelView = z.infer<typeof channelViewSchema>

export const adminInspectionViewSchema = z.object({
  businessNo: z.string().nullish(),
  receiptNo: z.string(),
  receivedQuantity: z.number().int().min(0),
  restockQuantity: z.number().int().min(0),
  recordedAt: isoDateTime,
})
export type AdminInspectionView = z.infer<typeof adminInspectionViewSchema>

export const adminReturnViewSchema = z.object({
  businessNo: z.string().nullish(),
  returnId: z.string(),
  returnNo: z.string(),
  orderNo: z.string(),
  memberId: z.string(),
  orderItemId: z.string(),
  warehouseId: z.string(),
  quantity: z.number().int().min(0),
  status: z.enum(['OPEN', 'INSPECTED']),
  receiptNo: z.string().nullable(),
  version: z.number().int().min(0),
  createdAt: isoDateTime,
  updatedAt: isoDateTime,
})
export type AdminReturnView = z.infer<typeof adminReturnViewSchema>

export const adminReturnDetailSchema = adminReturnViewSchema.extend({
  observedAt: isoDateTime,
  inspection: adminInspectionViewSchema.nullable(),
})
export type AdminReturnDetail = z.infer<typeof adminReturnDetailSchema>
export const returnViewSchema = adminReturnDetailSchema
export type ReturnView = AdminReturnDetail

export const inspectResultSchema = z.object({
  returnNo: z.string(),
  receiptNo: z.string(),
  status: z.literal('INSPECTED'),
  version: z.number().int(),
})

export const commandResourceSchema = z.object({
  resourceNo: z.string(),
  status: z.string(),
  version: z.number().int().min(0),
})
export type CommandResource = z.infer<typeof commandResourceSchema>

export const outboxPublicationSchema = z.object({
  id: z.string(),
  eventId: z.string(),
  eventType: z.string(),
  aggregateId: z.string(),
  status: z.string(),
  lastError: z.string().nullable(),
  firstFailureAt: isoDateTime.nullable(),
  lastFailureAt: isoDateTime.nullable(),
  publishedAt: isoDateTime.nullable(),
  publishedTopic: z.string().nullable(),
  version: z.number().int(),
  attemptBase: z.number().int(),
  replayCount: z.number().int(),
  failureCount: z.number().int(),
  publishedPartition: z.number().int().nullable(),
  publishedOffset: z.number().int().nullable(),
})
export type OutboxPublication = z.infer<typeof outboxPublicationSchema>

export const outboxReplayResultSchema = z.object({
  eventId: z.string(),
  version: z.number().int(),
  replayCount: z.number().int(),
})

export const outboxTaskSchema = z.object({
  id: z.string(),
  eventId: z.string().nullable(),
  referenceHash: z.string(),
  status: z.enum(['READY', 'RUNNING', 'DONE', 'REVIEW']),
  attempts: z.number().int(),
  nextRunAt: isoDateTime,
  lastError: z.string().nullable(),
})
export type OutboxTask = z.infer<typeof outboxTaskSchema>

export const reconciliationCaseSchema = z.object({
  id: z.string(),
  caseNo: z.string(),
  channelId: z.string(),
  statementDate: z.string(),
  differenceType: z.string(),
  status: z.string(),
  version: z.number().int().min(0),
})
export type ReconciliationCase = z.infer<typeof reconciliationCaseSchema>

export const reconciliationQueryResultSchema = z.object({
  operationNo: z.string(),
  caseNo: z.string(),
  caseVersion: z.number().int().min(1),
  initialStatus: z.literal('QUEUED'),
  acceptedAt: isoDateTime,
})

export const reconciliationOperationSchema = z.object({
  id: z.string(),
  operationNo: z.string(),
  caseNo: z.string(),
  action: z.enum(['QUERY_PAYMENT', 'QUERY_REFUND']),
  status: z.enum(['QUEUED', 'RUNNING', 'RESULT_ACCEPTED', 'REVIEW']),
  lastCode: z.string().nullable(),
  version: z.number().int().min(0),
  acceptedAt: isoDateTime,
})
export type ReconciliationOperation = z.infer<typeof reconciliationOperationSchema>

export const admissionPolicySchema = z.object({
  orderEnabled: z.boolean(),
  paymentEnabled: z.boolean(),
  version: z.number().int().min(0),
})
export type AdmissionPolicy = z.infer<typeof admissionPolicySchema>

export const orderStatusSchema = z.enum(['PENDING_PAYMENT', 'CLOSING', 'CANCELLED', 'PAID', 'FULFILLING', 'COMPLETED'])
export const financialStatusSchema = z.enum(['UNPAID', 'NO_PAYMENT_REQUIRED', 'PAID', 'PARTIALLY_REFUNDED', 'REFUNDED'])
export const paymentStatusSchema = z.enum(['READY', 'PENDING', 'SUCCEEDED', 'CLOSED', 'REVIEW'])
export const attemptStatusSchema = z.enum(['PREPARED', 'UNKNOWN', 'SUCCEEDED', 'CLOSED'])
export const receiptDispositionSchema = z.enum(['NORMAL', 'EXCESS', 'AMOUNT_MISMATCH', 'AFTER_CLOSE', 'AFTER_TERMINAL'])
export const jobTypeSchema = z.enum([
  'PAYMENT_RECOVER', 'PAYMENT_CALLBACK', 'ORDER_RESOLVE', 'REFUND_CALLBACK',
  'REFUND_RECOVER', 'REFUND_COMPENSATE', 'OUTBOX_PUBLISH', 'RECON_QUERY',
])
export const jobStatusSchema = z.enum(['READY', 'RUNNING', 'DONE', 'REVIEW'])
export const eligibilityPolicySchema = z.enum(['REGISTERED_DURING_CAMPAIGN_V1', 'NEVER_CONFIRMED_PAID_V1'])
export const refundQueueStatusSchema = z.enum(['ALL', ...refundStatusSchema.options])

export const adminOrderViewSchema = z.object({
  orderId: z.string(),
  orderNo: z.string(),
  businessNo: z.string().nullish(),
  memberId: z.string(),
  quoteId: z.string(),
  merchantNo: z.string(),
  warehouseId: z.string().nullable(),
  status: orderStatusSchema,
  financialStatus: financialStatusSchema,
  payableAmount: z.number().int().min(0),
  currency: z.string(),
  expiresAt: isoDateTime,
  paidAt: isoDateTime.nullable(),
  completedAt: isoDateTime.nullable(),
  createdAt: isoDateTime,
  version: z.number().int().min(0),
  paymentStartedAt: isoDateTime.nullable(),
  cancelledAt: isoDateTime.nullable(),
  updatedAt: isoDateTime,
})
export type AdminOrderView = z.infer<typeof adminOrderViewSchema>

export const adminOrderItemSchema = z.object({
  itemId: z.string(),
  skuId: z.string(),
  skuRevision: z.number().int().min(0),
  title: z.string(),
  specification: specificationSchema,
  stockType: z.string(),
  quantity: z.number().int().min(0),
  unitPrice: z.number().int().min(0),
  payableAmount: z.number().int().min(0),
  returnedQuantity: z.number().int().min(0),
})
export type AdminOrderItem = z.infer<typeof adminOrderItemSchema>

export const adminFulfillmentViewSchema = z.object({
  businessNo: z.string().nullish(),
  fulfillmentNo: z.string(),
  deliveredAt: isoDateTime,
  completedAt: isoDateTime.nullable(),
  version: z.number().int().min(0),
})
export type AdminFulfillmentView = z.infer<typeof adminFulfillmentViewSchema>

export const adminOrderDetailSchema = adminOrderViewSchema.extend({
  observedAt: isoDateTime,
  items: z.array(adminOrderItemSchema),
  fulfillment: adminFulfillmentViewSchema.nullable(),
})
export type AdminOrderDetail = z.infer<typeof adminOrderDetailSchema>

export const adminPaymentViewSchema = z.object({
  paymentNo: z.string(),
  businessNo: z.string().nullish(),
  orderId: z.string(),
  orderNo: z.string(),
  memberId: z.string(),
  status: paymentStatusSchema,
  expectedAmount: z.number().int().min(0),
  currency: z.string(),
  activeAttemptNo: z.string().nullable(),
  createdAt: isoDateTime,
  updatedAt: isoDateTime,
  version: z.number().int().min(0),
  succeededReceiptNo: z.string().nullable(),
  closeFenceAt: isoDateTime.nullable(),
})
export type AdminPaymentView = z.infer<typeof adminPaymentViewSchema>
export const adminPaymentDetailSchema = adminPaymentViewSchema.extend({ observedAt: isoDateTime })
export type AdminPaymentDetail = z.infer<typeof adminPaymentDetailSchema>

export const adminAttemptViewSchema = z.object({
  attemptId: z.string(),
  paymentNo: z.string(),
  attemptNo: z.string(),
  channelId: z.string(),
  channelCode: z.string(),
  status: attemptStatusSchema,
  submittedAt: isoDateTime.nullable(),
  closedAt: isoDateTime.nullable(),
  hasCloseEvidence: z.boolean(),
  createdAt: isoDateTime,
  updatedAt: isoDateTime,
  version: z.number().int().min(0),
})
export type AdminAttemptView = z.infer<typeof adminAttemptViewSchema>

export const adminReceiptViewSchema = z.object({
  receiptNo: z.string(),
  paymentNo: z.string(),
  orderNo: z.string(),
  attemptNo: z.string(),
  channelCode: z.string(),
  amount: z.number().int().min(0),
  currency: z.string(),
  disposition: receiptDispositionSchema,
  providerPaidAt: isoDateTime,
  confirmedAt: isoDateTime,
  createdAt: isoDateTime,
  refundedAmount: z.number().int().min(0),
  refundReservedAmount: z.number().int().min(0),
  unreservedAmount: z.number().int().min(0),
  version: z.number().int().min(0),
})
export type AdminReceiptView = z.infer<typeof adminReceiptViewSchema>
export const adminReceiptDetailSchema = adminReceiptViewSchema.extend({ observedAt: isoDateTime })
export type AdminReceiptDetail = z.infer<typeof adminReceiptDetailSchema>

export const adminRefundViewSchema = z.object({
  refundNo: z.string(),
  businessNo: z.string().nullish(),
  orderNo: z.string(),
  memberId: z.string(),
  amount: z.number().int().min(0),
  currency: z.string(),
  status: refundStatusSchema,
  approvalRef: z.string().nullable(),
  approvalDeadline: isoDateTime.nullable(),
  policyVersion: z.number().int().min(0),
  version: z.number().int().min(0),
  createdAt: isoDateTime,
})
export type AdminRefundView = z.infer<typeof adminRefundViewSchema>

export const adminRefundItemSchema = z.object({
  componentType: z.string(),
  orderItemId: z.string().nullable(),
  quantity: z.number().int().min(0),
  amount: z.number().int().min(0),
})
export type AdminRefundItem = z.infer<typeof adminRefundItemSchema>

export const adminRefundDetailSchema = adminRefundViewSchema.extend({
  observedAt: isoDateTime,
  items: z.array(adminRefundItemSchema),
  processingReasonCode: z.string().nullable(),
})
export type AdminRefundDetail = z.infer<typeof adminRefundDetailSchema>

export const adminQuoteViewSchema = z.object({
  quoteId: z.string(),
  memberId: z.string(),
  payableAmount: z.number().int().min(0),
  currency: z.string(),
  pricingPolicy: z.string(),
  createdAt: isoDateTime,
  expiresAt: isoDateTime,
  version: z.number().int().min(0),
})
export type AdminQuoteView = z.infer<typeof adminQuoteViewSchema>

export const quoteItemSchema = z.object({
  skuId: z.string(),
  skuRevision: z.number().int().min(0),
  title: z.string().min(1),
  specification: specificationSchema,
  stockType: stockTypeSchema,
  quantity: z.number().int().min(1),
  unitPrice: z.number().int().min(0),
  payableAmount: z.number().int().min(0),
})
export type QuoteItem = z.infer<typeof quoteItemSchema>

export const adminQuoteDetailSchema = adminQuoteViewSchema.extend({
  observedAt: isoDateTime,
  items: z.array(quoteItemSchema),
})
export type AdminQuoteDetail = z.infer<typeof adminQuoteDetailSchema>

export const adminJobViewSchema = z.object({
  jobId: z.string(),
  jobType: jobTypeSchema,
  status: jobStatusSchema,
  attempts: z.number().int().min(0),
  nextRunAt: isoDateTime,
  leaseUntil: isoDateTime.nullable(),
  leaseExpired: z.boolean(),
  lastError: z.string().nullable(),
  createdAt: isoDateTime,
  updatedAt: isoDateTime,
  version: z.number().int().min(0),
})
export type AdminJobView = z.infer<typeof adminJobViewSchema>

export const adminBusinessReferenceSchema = z.object({
  resourceType: z.string(),
  resourceNo: z.string(),
})
export type AdminBusinessReference = z.infer<typeof adminBusinessReferenceSchema>

export const adminJobDetailSchema = adminJobViewSchema.extend({
  observedAt: isoDateTime,
  businessReference: adminBusinessReferenceSchema.nullable(),
})
export type AdminJobDetail = z.infer<typeof adminJobDetailSchema>

export const adminOrderEvidenceSchema = z.object({
  orderId: z.string(),
  memberId: z.string(),
  evidenceVersion: z.number().int().min(0),
  firstOrderPolicy: z.literal('FIRST_CONFIRMED_PAID_ORDER_V1'),
  isFirstConfirmedPaidOrder: z.boolean(),
  paidAmount: z.number().int().min(0),
  refundedAmount: z.number().int().min(0),
  pendingRefundAmount: z.number().int().min(0),
  netPaidAmount: z.number().int().min(0),
  conservativeEligibleAmount: z.number().int().min(0),
  currency: z.string(),
  paidAt: isoDateTime.nullable(),
  completedAt: isoDateTime.nullable(),
  asOf: isoDateTime,
  orderNo: z.string(),
})
export type AdminOrderEvidence = z.infer<typeof adminOrderEvidenceSchema>

export const customerEligibilityEvidenceSchema = z.object({
  memberId: z.string(),
  registeredAt: isoDateTime,
  everPaid: z.boolean(),
  firstPaidOrderNo: z.string().nullable(),
  factVersion: z.number().int().min(0),
  eligible: z.boolean(),
  reasonCodes: z.array(z.string()),
  evaluatedAt: isoDateTime,
  policyVersion: eligibilityPolicySchema,
  everPaidAtEvaluation: z.boolean(),
  observedAt: isoDateTime,
})
export type CustomerEligibilityEvidence = z.infer<typeof customerEligibilityEvidenceSchema>

export const customerEligibilityResultSchema = z.object({
  status: z.enum(['READY', 'NOT_FOUND']),
  evidence: customerEligibilityEvidenceSchema.nullable(),
})
export type CustomerEligibilityResult = z.infer<typeof customerEligibilityResultSchema>

const minorTotal = z.string().regex(/^(0|[1-9][0-9]*)$/)
export const operationsSummarySchema = z.object({
  from: isoDateTime, to: isoDateTime, observedAt: isoDateTime, currency: currencyCode,
  orderCount: z.number().int().nonnegative(), orderPayable: minorTotal,
  paidOrderCount: z.number().int().nonnegative(),
  normalReceipt: minorTotal, abnormalReceipt: minorTotal,
  normalRefund: minorTotal, abnormalRefund: minorTotal,
  netReceipt: z.string().regex(/^(0|-?[1-9][0-9]*)$/),
})
const operationsFactKind = z.enum(['NORMAL_RECEIPT', 'ABNORMAL_RECEIPT', 'NORMAL_REFUND', 'ABNORMAL_REFUND'])
export type OperationsScope = z.infer<typeof operationsFactKind> | 'NET'
export const operationsFinancialFactSchema = z.object({
  ledgerNo: z.string(), orderNo: z.string(), receiptNo: z.string(), refundNo: z.string().nullable(),
  kind: operationsFactKind, amount: z.number().int().positive(), currency: currencyCode, confirmedAt: isoDateTime,
})

export function pageSchema<T extends z.ZodType>(item: T) {
  return z.object({
    items: z.array(item),
    nextAfter: z.string().regex(/^[1-9][0-9]{0,18}$/).nullable(),
    hasMore: z.boolean(),
    observedAt: isoDateTime,
  })
}
export type Page<T> = { items: T[]; nextAfter: string | null; hasMore: boolean; observedAt: string }

export function parseWith<T>(schema: z.ZodType<T>, value: unknown): T {
  const result = schema.safeParse(value)
  if (!result.success) {
    throw Object.assign(new Error('接口返回与 OpenAPI 合同不符'), { name: 'ContractMismatch' })
  }
  return result.data
}

export function parseArray<T>(schema: z.ZodType<T>, value: unknown): T[] {
  return parseWith(z.array(schema), value)
}

export function parsePage<T>(schema: z.ZodType<T>, value: unknown): Page<T> {
  return parseWith(pageSchema(schema), value)
}
