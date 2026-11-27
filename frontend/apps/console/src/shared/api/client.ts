import { runtimeConfig } from '../config/runtime'
import {
  adminAttemptViewSchema,
  adminJobDetailSchema,
  adminJobViewSchema,
  adminOrderDetailSchema,
  adminOrderEvidenceSchema,
  adminOrderViewSchema,
  adminPaymentDetailSchema,
  adminPaymentViewSchema,
  adminQuoteDetailSchema,
  adminQuoteViewSchema,
  adminReceiptDetailSchema,
  adminReceiptViewSchema,
  adminRefundDetailSchema,
  adminReturnDetailSchema,
  adminReturnViewSchema,
  admissionPolicySchema,
  balanceSchema,
  channelViewSchema,
  commandResourceSchema,
  consentDocumentSchema,
  customerEligibilityResultSchema,
  failureSchema,
  inspectResultSchema,
  ledgerEntrySchema,
  memberSchema,
  merchantSchema,
  operationAuditPageSchema,
  numberedOrderPageSchema,
  operationsFinancialFactSchema,
  operationsSummarySchema,
  type OperationsScope,
  outboxPublicationSchema,
  outboxReplayResultSchema,
  outboxTaskSchema,
  parseArray,
  parsePage,
  parseWith,
  adminProductSchema,
  productSchema,
  reconciliationCaseSchema,
  reconciliationOperationSchema,
  reconciliationQueryResultSchema,
  refundPolicySchema,
  refundAuditHistorySchema,
  refundQueueItemSchema,
  resourceSchema,
  skuSchema,
  warehouseSchema,
  type AdminAttemptView,
  type AdminJobDetail,
  type AdminJobView,
  type AdminOrderDetail,
  type AdminOrderEvidence,
  type AdminOrderView,
  type AdminPaymentDetail,
  type AdminPaymentView,
  type AdminQuoteDetail,
  type AdminQuoteView,
  type AdminReceiptDetail,
  type AdminReceiptView,
  type AdminRefundDetail,
  type AdminReturnDetail,
  type AdminReturnView,
  type AdmissionPolicy,
  type Balance,
  type ChannelView,
  type CommandResource,
  type ConsentDocument,
  type CustomerEligibilityResult,
  type Failure,
  type LedgerEntry,
  type Member,
  type Merchant,
  type OutboxPublication,
  type OutboxTask,
  type Page,
  type Product,
  type ReconciliationCase,
  type ReconciliationOperation,
  type RefundPolicy,
  type RefundQueueItem,
  type Resource,
  type ReturnView,
  type Sku,
  type Warehouse,
} from './schemas'
import { bodyHash, cursorQuery, numberedPageQuery, pageQuery, refundQueueQuery, rememberIdempotencyKey, strongEtag, type QueryScalar, type RefundQueueQuery } from './keys'

const HTTP_NOT_FOUND = 404
const HTTP_METHOD_NOT_ALLOWED = 405

let accessTokenProvider: () => string | undefined = () => undefined
let unauthorizedHandler: () => void = () => undefined

export function setAccessTokenProvider(provider: () => string | undefined) {
  accessTokenProvider = provider
}

export function setUnauthorizedHandler(handler: () => void) {
  unauthorizedHandler = handler
}

export class ApiProblem extends Error {
  readonly status: number
  readonly code?: string
  readonly retryable?: boolean
  readonly traceId?: string

  constructor(readonly failure: Failure, status: number, readonly retryAfterSeconds?: number) {
    super(failure.message)
    this.name = 'ApiProblem'
    this.status = status
    this.code = failure.code
    this.retryable = failure.retryable
    this.traceId = failure.traceId
  }
}

export function isVersionConflict(error: unknown): error is ApiProblem {
  return error instanceof ApiProblem && (error.status === 409 || error.status === 412)
}

function fallbackFailure(status: number, statusText: string, detail?: string): Failure {
  return {
    code: `HTTP_${status}`,
    message: detail || statusText || '请求失败',
    traceId: '',
    retryable: status === 503 || status === 429,
  }
}

function mergeHeaders(init?: RequestInit): Headers {
  const headers = new Headers(init?.headers)
  const token = accessTokenProvider()
  if (token && !headers.has('Authorization')) headers.set('Authorization', `Bearer ${token}`)
  const method = (init?.method ?? 'GET').toUpperCase()
  if (init?.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (method !== 'GET' && method !== 'HEAD' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (!headers.has('X-Request-Id')) headers.set('X-Request-Id', crypto.randomUUID())
  return headers
}

async function request<T>(path: string, init: RequestInit | undefined, parse: (value: unknown) => T): Promise<T> {
  const method = (init?.method ?? 'GET').toUpperCase()
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), runtimeConfig.requestTimeoutMs)
  const parentAbort = init?.signal
  const onAbort = () => controller.abort()
  parentAbort?.addEventListener('abort', onAbort)
  try {
    const response = await fetch(`${runtimeConfig.apiBaseUrl}${path}`, {
      ...init,
      method,
      headers: mergeHeaders(init),
      signal: controller.signal,
    })
    if (response.status === 401) unauthorizedHandler()
    if (!response.ok) {
      const raw = await response.json().catch(() => undefined)
      const parsed = failureSchema.safeParse(raw)
      const retryAfter = response.headers.get('Retry-After')
      const retryAfterSeconds = retryAfter && /^\d+$/.test(retryAfter) ? Number(retryAfter) : undefined
      throw new ApiProblem(parsed.success ? parsed.data : fallbackFailure(response.status, response.statusText), response.status, retryAfterSeconds)
    }
    if (response.status === 204) return undefined as T
    const text = await response.text()
    if (!text) return parse(undefined)
    return parse(JSON.parse(text) as unknown)
  } catch (cause) {
    if (cause instanceof ApiProblem) throw cause
    if (cause instanceof Error && cause.name === 'ContractMismatch') {
      throw new ApiProblem(fallbackFailure(502, 'Bad Gateway', '接口返回与 OpenAPI 合同不符'), 502)
    }
    if (cause instanceof DOMException && cause.name === 'AbortError') {
      throw new ApiProblem(fallbackFailure(504, 'Gateway Timeout', '请求超时或已取消'), 504)
    }
    throw new ApiProblem(fallbackFailure(0, 'Network Error', cause instanceof Error ? cause.message : '网络不可用'), 0)
  } finally {
    window.clearTimeout(timeout)
    parentAbort?.removeEventListener('abort', onAbort)
  }
}

function commandHeaders(action: string, body: unknown, version?: number): Record<string, string> {
  const headers: Record<string, string> = {
    'Idempotency-Key': rememberIdempotencyKey(action, bodyHash({ body, version })),
  }
  if (version !== undefined) headers['If-Match'] = strongEtag(version)
  return headers
}

export function describeProblem(error: unknown): { title: string; detail: string } {
  if (!(error instanceof ApiProblem)) {
    return { title: '请求失败', detail: error instanceof Error ? error.message : '未知错误' }
  }
  if (error.status === 429) {
    const trace = error.traceId ? ` traceId=${error.traceId}` : ''
    return { title: '管理查询繁忙', detail: `${error.message} 请在 ${error.retryAfterSeconds ?? 1} 秒后重试。${trace}` }
  }
  if (error.status === 503 && error.code === 'EVIDENCE_DISABLED') {
    const trace = error.traceId ? ` traceId=${error.traceId}` : ''
    return { title: '证据入口已关闭', detail: `${error.message}${trace}` }
  }
  if (error.status === 503 && error.code === 'EVIDENCE_UNAVAILABLE') {
    const trace = error.traceId ? ` traceId=${error.traceId}` : ''
    return { title: '证据暂不可用', detail: `${error.message} 请保留接口返回，不要用零值填补。${trace}` }
  }
  if (error.status === 503 && error.code === 'ADMIN_QUERY_UNAVAILABLE') {
    const trace = error.traceId ? ` traceId=${error.traceId}` : ''
    return { title: '管理查询暂不可用', detail: `${error.message}${trace}` }
  }
  const titles: Record<number, string> = {
    0: '网络不可用',
    400: '请求格式无效',
    401: '需要重新登录',
    403: '没有权限',
    404: '资源不存在',
    409: '版本或幂等冲突',
    412: '版本已过期',
    422: '业务条件不满足',
    428: '缺少 If-Match',
    502: '合同校验失败',
    503: '入口暂不可用',
    504: '请求超时',
  }
  const title = titles[error.status] ?? `请求失败（${error.status}）`
  const frozen = error.code === 'MEMBER_FROZEN' ? '会员已冻结。' : ''
  const hint = error.status === 409 || error.status === 412
    ? '请重新读取最新版本后再决定是否提交，不要换幂等键盲重试。'
    : error.status === 403
      ? '令牌需要 aud=transaction-center，且 scope 含 trade.admin；租户声明须为正整数文本 tenant_id。'
      : error.status === 503
        ? '请保留原幂等键稍后重试。'
        : ''
  const trace = error.traceId ? ` traceId=${error.traceId}` : ''
  return { title, detail: `${frozen}${error.message}${hint ? ` ${hint}` : ''}${trace}` }
}

const admin = '/api/v1/trade/admin'

export type OperationsRange = { createdFrom: string; createdTo: string; currency: string }

export const api = {
  members: (query: { after?: string; limit?: number; memberId?: string; status?: string; registeredFrom?: string; registeredTo?: string } = {}) =>
    request(`${admin}/members${pageQuery(query)}`, undefined, (value) => parseArray(memberSchema, value)),
  changeMemberStatus: (id: string, body: { expectedVersion: number; targetStatus: 'ACTIVE' | 'FROZEN'; reason: string }) =>
    request(`${admin}/members/${encodeURIComponent(id)}:change-status`, {
      method: 'POST',
      headers: commandHeaders(`member-status:${id}:${body.targetStatus}:${body.expectedVersion}`, body, body.expectedVersion),
      body: JSON.stringify(body),
    }, (value) => parseWith(resourceSchema, value)),

  consentDocuments: (after = 0, limit = 20) =>
    request(`/api/v1/trade/consent-documents${cursorQuery(after, limit)}`, undefined, (value) => parseArray(consentDocumentSchema, value)),
  publishConsentDocument: (body: { purpose: string; documentVersion: string; title: string; contentUrl: string; contentHash: string }) =>
    request(`${admin}/consent-documents`, {
      method: 'POST',
      headers: commandHeaders(`consent:${body.purpose}:${body.documentVersion}`, body),
      body: JSON.stringify(body),
    }, (value) => parseWith(resourceSchema, value)),

  merchant: () => request(`${admin}/merchant`, undefined, (value) => parseWith(merchantSchema, value)),
  createMerchant: (body: { merchantNo: string; name: string }) =>
    request(`${admin}/merchant`, {
      method: 'POST',
      headers: commandHeaders('merchant:create', body),
      body: JSON.stringify(body),
    }, (value) => parseWith(resourceSchema, value)),
  changeMerchantStatus: (body: { expectedVersion: number; targetStatus: 'ACTIVE' | 'FROZEN'; reason: string }) =>
    request(`${admin}/merchant:change-status`, {
      method: 'POST',
      headers: commandHeaders(`merchant-status:${body.targetStatus}:${body.expectedVersion}`, body, body.expectedVersion),
      body: JSON.stringify(body),
    }, (value) => parseWith(resourceSchema, value)),

  searchProducts: (query: { after?: string; limit?: number; productNo?: string; skuNo?: string; name?: string; status?: string } = {}) =>
    request(`${admin}/products/search${pageQuery(query)}`, undefined, (value) => parseArray(adminProductSchema, value)),
  product: (id: string) =>
    request(`${admin}/products/${encodeURIComponent(id)}`, undefined, (value) => parseWith(productSchema, value)),
  createProduct: (body: { productNo: string; name: string; skuNo: string; unitPrice: number; currency: string }) =>
    request(`${admin}/products`, {
      method: 'POST',
      headers: commandHeaders(`product:create:${body.productNo}`, body),
      body: JSON.stringify(body),
    }, (value) => parseWith(resourceSchema, value)),
  editProduct: (id: string, body: { expectedVersion: number; name: string; unitPrice: number; currency: string }) =>
    request(`${admin}/products/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      headers: commandHeaders(`product:edit:${id}:${body.expectedVersion}`, body, body.expectedVersion),
      body: JSON.stringify(body),
    }, (value) => parseWith(resourceSchema, value)),
  publishProduct: (id: string, expectedVersion: number) =>
    request(`${admin}/products/${encodeURIComponent(id)}:publish`, {
      method: 'POST',
      headers: commandHeaders(`product:publish:${id}:${expectedVersion}`, { expectedVersion }, expectedVersion),
      body: JSON.stringify({ expectedVersion }),
    }, (value) => parseWith(resourceSchema, value)),
  unpublishProduct: (id: string, expectedVersion: number) =>
    request(`${admin}/products/${encodeURIComponent(id)}:unpublish`, {
      method: 'POST',
      headers: commandHeaders(`product:unpublish:${id}:${expectedVersion}`, { expectedVersion }, expectedVersion),
      body: JSON.stringify({ expectedVersion }),
    }, (value) => parseWith(resourceSchema, value)),
  productSkus: (id: string, after = 0, limit = 20) =>
    request(`${admin}/products/${encodeURIComponent(id)}/skus${cursorQuery(after, limit)}`, undefined, (value) => parseArray(skuSchema, value)),
  addSku: (id: string, body: { expectedVersion: number; skuNo: string; unitPrice: number; currency: string; stockType: 'PHYSICAL' | 'SERVICE'; specification: Record<string, string> }) =>
    request(`${admin}/products/${encodeURIComponent(id)}/skus`, {
      method: 'POST',
      headers: commandHeaders(`sku:add:${id}:${body.skuNo}:${body.expectedVersion}`, body, body.expectedVersion),
      body: JSON.stringify(body),
    }, (value) => parseWith(resourceSchema, value)),
  editSku: (id: string, skuId: string, body: { expectedVersion: number; unitPrice: number; currency: string; stockType: 'PHYSICAL' | 'SERVICE'; specification: Record<string, string> }) =>
    request(`${admin}/products/${encodeURIComponent(id)}/skus/${encodeURIComponent(skuId)}`, {
      method: 'PATCH',
      headers: commandHeaders(`sku:edit:${id}:${skuId}:${body.expectedVersion}`, body, body.expectedVersion),
      body: JSON.stringify(body),
    }, (value) => parseWith(resourceSchema, value)),

  warehouses: (after = 0, limit = 20) =>
    request(`${admin}/warehouses${cursorQuery(after, limit)}`, undefined, (value) => parseArray(warehouseSchema, value)),
  createWarehouse: (body: { warehouseNo: string; name: string }) =>
    request(`${admin}/warehouses`, {
      method: 'POST',
      headers: commandHeaders(`warehouse:${body.warehouseNo}`, body),
      body: JSON.stringify(body),
    }, (value) => parseWith(resourceSchema, value)),
  inventories: (query: { after?: string; limit?: number; warehouseId?: string; skuId?: string; maxAvailable?: string } = {}) =>
    request(`${admin}/inventories${pageQuery(query)}`, undefined, (value) => parseArray(balanceSchema, value)),
  inventoryLedgers: (warehouseId: number, skuId: number, after = 0, limit = 20) =>
    request(`${admin}/inventory-ledgers${cursorQuery(after, limit)}&warehouseId=${warehouseId}&skuId=${skuId}`, undefined, (value) => parseArray(ledgerEntrySchema, value)),
  adjustInventory: (body: { warehouseId: number; skuId: number; delta: number; reason: string }) =>
    request(`${admin}/inventory-adjustments`, {
      method: 'POST',
      headers: commandHeaders(`adjust:${body.warehouseId}:${body.skuId}:${body.delta}:${body.reason}`, body),
      body: JSON.stringify(body),
    }, (value) => parseWith(resourceSchema, value)),

  refundPolicy: () => request(`${admin}/refund-policy`, undefined, (value) => parseWith(refundPolicySchema, value)),
  updateRefundPolicy: (body: { currency: string; autoApproveLimit: number; expectedVersion: number }) =>
    request(`${admin}/refund-policy`, {
      method: 'PATCH',
      headers: commandHeaders(`refund-policy:${body.expectedVersion}`, body, body.expectedVersion),
      body: JSON.stringify(body),
    }, (value) => parseWith(refundPolicySchema, value)),
  refunds: (query: RefundQueueQuery = {}, signal?: AbortSignal) =>
    request(`${admin}/refunds${refundQueueQuery(query)}`, { signal }, (value) => parseArray(refundQueueItemSchema, value)),
  refund: (refundNo: string) =>
    request(`${admin}/refunds/${encodeURIComponent(refundNo)}`, undefined, (value) => parseWith(adminRefundDetailSchema, value)),
  operationAudits: (query: { limit?: number; cursor?: string; resourceType?: string; resourceRef?: string; createdFrom?: string; createdTo?: string }, signal?: AbortSignal) => {
    const search = new URLSearchParams()
    for (const [key, value] of Object.entries(query)) {
      if (value) search.set(key, String(value))
    }
    return request(`${admin}/operation-audits${search.size ? '?' + search.toString() : ''}`,
      { signal }, (value) => parseWith(operationAuditPageSchema, value))
  },
  refundAudit: (refundNo: string, { cursor, limit }: { cursor?: string; limit?: number }, signal?: AbortSignal) => {
    const search = new URLSearchParams()
    if (cursor) search.set('cursor', cursor)
    if (limit !== undefined) search.set('limit', String(limit))
    return request(`${admin}/refunds/${encodeURIComponent(refundNo)}/operation-audits${search.size ? '?' + search.toString() : ''}`,
      { signal }, (value) => parseWith(refundAuditHistorySchema, value))
  },

  paymentChannels: (after = 0, limit = 20) =>
    request(`${admin}/payment-channels${cursorQuery(after, limit)}`, undefined, (value) => parseArray(channelViewSchema, value)),
  createPaymentChannel: (body: {
    channelCode: string
    channelMerchantNo: string
    currency: string
    credentialRef: string
    returnUrlId: string
    returnUrl: string
  }) =>
    request(`${admin}/payment-channels`, {
      method: 'POST',
      headers: commandHeaders(`channel:${body.channelCode}`, body),
      body: JSON.stringify(body),
    }, (value) => parseWith(resourceSchema, value)),

  returns: (query: Record<string, QueryScalar> = {}, signal?: AbortSignal) =>
    request(`${admin}/returns${pageQuery(query)}`, { signal }, (value) => parsePage(adminReturnViewSchema, value)),
  returnByNo: (returnNo: string) =>
    request(`${admin}/returns/${encodeURIComponent(returnNo)}`, undefined, (value) => parseWith(adminReturnDetailSchema, value)),
  createReturn: (body: { orderItemId: string; warehouseId: string; quantity: number }) =>
    request(`${admin}/returns`, {
      method: 'POST',
      headers: commandHeaders(`return:${body.orderItemId}:${body.warehouseId}:${body.quantity}`, body),
      body: JSON.stringify(body),
    }, (value) => parseWith(commandResourceSchema, value)),
  inspectReturn: (returnNo: string, expectedVersion: number, body: { result: 'ACCEPTED' | 'PARTIAL' | 'REJECTED'; restockQuantity: number; evidenceRef: string }) =>
    request(`${admin}/returns/${encodeURIComponent(returnNo)}:inspect`, {
      method: 'POST',
      headers: commandHeaders(`inspect:${returnNo}:${expectedVersion}:${body.result}`, { ...body, expectedVersion }, expectedVersion),
      body: JSON.stringify(body),
    }, (value) => parseWith(inspectResultSchema, value)),

  createFulfillment: (orderNo: string, body: { sourceRequestId: string; deliveryRef: string }) =>
    request(`${admin}/orders/${encodeURIComponent(orderNo)}/fulfillments`, {
      method: 'POST',
      headers: commandHeaders(`fulfill:${orderNo}:${body.sourceRequestId}`, body),
      body: JSON.stringify(body),
    }, (value) => parseWith(commandResourceSchema, value)),

  outboxPublications: (state: 'PENDING' | 'SENT' | 'REVIEW' = 'REVIEW', after = 0, limit = 50) =>
    request(`${admin}/outbox-publications${cursorQuery(after, limit)}&state=${state}`, undefined, (value) => parseArray(outboxPublicationSchema, value)),
  outboxTasks: (state: 'READY' | 'RUNNING' | 'DONE' | 'REVIEW' = 'REVIEW', after = 0, limit = 50) =>
    request(`${admin}/outbox-publications/tasks${cursorQuery(after, limit)}&state=${state}`, undefined, (value) => parseArray(outboxTaskSchema, value)),
  replayOutbox: (eventId: string, body: { expectedVersion: number; reason: string; ticketRef: string }) =>
    request(`${admin}/outbox-publications/${encodeURIComponent(eventId)}:replay`, {
      method: 'POST',
      headers: commandHeaders(`replay:${eventId}:${body.expectedVersion}`, body, body.expectedVersion),
      body: JSON.stringify(body),
    }, (value) => parseWith(outboxReplayResultSchema, value)),

  reconciliationCases: (state: 'OPEN' | 'PROCESSING' | 'CLOSED' = 'OPEN', after = 0, limit = 50, number?: string) =>
    request(`${admin}/reconciliation-cases${cursorQuery(after, limit)}&state=${state}${number ? `&number=${encodeURIComponent(number)}` : ""}`, undefined, (value) => parseArray(reconciliationCaseSchema, value)),
  reconciliationOperations: (state: 'QUEUED' | 'RUNNING' | 'RESULT_ACCEPTED' | 'REVIEW' = 'REVIEW', after = 0, limit = 50, number?: string) =>
    request(`${admin}/reconciliation-operations${cursorQuery(after, limit)}&state=${state}${number ? `&number=${encodeURIComponent(number)}` : ""}`, undefined, (value) => parseArray(reconciliationOperationSchema, value)),
  queryReconciliationCase: (caseNo: string, body: { expectedVersion: number; action: 'QUERY_PAYMENT' | 'QUERY_REFUND'; reason: string; ticketRef: string }) =>
    request(`${admin}/reconciliation-cases/${encodeURIComponent(caseNo)}:query`, {
      method: 'POST',
      headers: commandHeaders(`recon:${caseNo}:${body.action}:${body.expectedVersion}`, body, body.expectedVersion),
      body: JSON.stringify(body),
    }, (value) => parseWith(reconciliationQueryResultSchema, value)),

  operationsSummary: (range: OperationsRange, signal?: AbortSignal) =>
    request(`${admin}/operations/summary?${new URLSearchParams(range)}`, { signal }, (value) => parseWith(operationsSummarySchema, value)),
  operationsFinancialFacts: (query: OperationsRange & { scope: OperationsScope; after: string; limit?: number }, signal?: AbortSignal) =>
    request(`${admin}/operations/financial-facts${pageQuery(query)}`, { signal }, (value) => parsePage(operationsFinancialFactSchema, value)),
  orders: (query: Record<string, QueryScalar> = {}) =>
    request(`${admin}/orders${pageQuery(query)}`, undefined, (value) => parsePage(adminOrderViewSchema, value)),
  numberedOrders: async (page: number, limit: number, filters: Record<string, QueryScalar>, signal?: AbortSignal) => {
    try {
      return await request(`${admin}/order-pages${numberedPageQuery(page, limit, filters)}`,
        { signal }, (value) => parseWith(numberedOrderPageSchema, value))
    } catch (cause) {
      // 仅在路由明确不可用时回退。权限和暂时故障仍按原错误处理。
      if (cause instanceof ApiProblem && (cause.status === HTTP_NOT_FOUND || cause.status === HTTP_METHOD_NOT_ALLOWED)) return undefined
      throw cause
    }
  },
  order: (orderNo: string) =>
    request(`${admin}/orders/${encodeURIComponent(orderNo)}`, undefined, (value) => parseWith(adminOrderDetailSchema, value)),
  payments: (query: Record<string, QueryScalar> = {}, signal?: AbortSignal) =>
    request(`${admin}/payments${pageQuery(query)}`, { signal }, (value) => parsePage(adminPaymentViewSchema, value)),
  payment: (paymentNo: string) =>
    request(`${admin}/payments/${encodeURIComponent(paymentNo)}`, undefined, (value) => parseWith(adminPaymentDetailSchema, value)),
  paymentAttempts: (paymentNo: string, query: Record<string, QueryScalar> = {}) =>
    request(`${admin}/payments/${encodeURIComponent(paymentNo)}/attempts${pageQuery(query)}`, undefined, (value) => parsePage(adminAttemptViewSchema, value)),
  paymentReceipts: (query: Record<string, QueryScalar> = {}, signal?: AbortSignal) =>
    request(`${admin}/payment-receipts${pageQuery(query)}`, { signal }, (value) => parsePage(adminReceiptViewSchema, value)),
  paymentReceipt: (receiptNo: string) =>
    request(`${admin}/payment-receipts/${encodeURIComponent(receiptNo)}`, undefined, (value) => parseWith(adminReceiptDetailSchema, value)),
  quotes: (query: Record<string, QueryScalar> = {}) =>
    request(`${admin}/quotes${pageQuery(query)}`, undefined, (value) => parsePage(adminQuoteViewSchema, value)),
  quote: (quoteId: string) =>
    request(`${admin}/quotes/${encodeURIComponent(quoteId)}`, undefined, (value) => parseWith(adminQuoteDetailSchema, value)),
  jobs: (query: Record<string, QueryScalar> = {}) =>
    request(`${admin}/jobs${pageQuery(query)}`, undefined, (value) => parsePage(adminJobViewSchema, value)),
  job: (jobId: string) =>
    request(`${admin}/jobs/${encodeURIComponent(jobId)}`, undefined, (value) => parseWith(adminJobDetailSchema, value)),
  orderReferralEvidence: (orderNo: string, signal?: AbortSignal) =>
    request(`${admin}/orders/${encodeURIComponent(orderNo)}/referral-evidence`, { signal }, (value) => parseWith(adminOrderEvidenceSchema, value)),
  queryMemberEligibility: (memberId: string, body: { policyVersion: 'REGISTERED_DURING_CAMPAIGN_V1' | 'NEVER_CONFIRMED_PAID_V1'; campaignStartAt?: string; evaluatedAt?: string }) =>
    request(`${admin}/members/${encodeURIComponent(memberId)}/eligibility:query`, {
      method: 'POST',
      body: JSON.stringify(body),
    }, (value) => parseWith(customerEligibilityResultSchema, value)),

  admissionPolicy: () => request(`${admin}/admission-policy`, undefined, (value) => parseWith(admissionPolicySchema, value)),
  updateAdmissionPolicy: (version: number, body: { orderEnabled: boolean; paymentEnabled: boolean; reason: string }) =>
    request(`${admin}/admission-policy`, {
      method: 'PATCH',
      headers: commandHeaders(`admission:${version}`, body, version),
      body: JSON.stringify(body),
    }, (value) => parseWith(admissionPolicySchema, value)),
}

export type {
  AdminAttemptView,
  AdminJobDetail,
  AdminJobView,
  AdminOrderDetail,
  AdminOrderEvidence,
  AdminOrderView,
  AdminPaymentDetail,
  AdminPaymentView,
  AdminQuoteDetail,
  AdminQuoteView,
  AdminReceiptDetail,
  AdminReceiptView,
  AdminRefundDetail,
  AdminReturnDetail,
  AdminReturnView,
  AdmissionPolicy,
  Balance,
  ChannelView,
  CommandResource,
  ConsentDocument,
  CustomerEligibilityResult,
  LedgerEntry,
  Member,
  Merchant,
  OutboxPublication,
  OutboxTask,
  Page,
  Product,
  ReconciliationCase,
  ReconciliationOperation,
  RefundPolicy,
  RefundQueueItem,
  Resource,
  ReturnView,
  Sku,
  Warehouse,
}
