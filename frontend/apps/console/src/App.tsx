import { lazy, type ReactNode } from 'react'
import { Route, Routes } from 'react-router-dom'
import { AppShell } from './app/AppShell'
import { RequireAuth } from './app/RequireAuth'
import { RequireScope } from './app/RequireScope'
import { runtimeConfigError } from './shared/config/runtime'
import { TRADE_ADMIN, TRADE_ADMIN_JOBS_READ } from './shared/auth/auth-context'
import { LoginPage } from './features/auth/LoginPage'
import { AuthCallbackPage } from './features/auth/AuthCallbackPage'
const OverviewPage = lazy(() => import('./features/overview/OverviewPage').then((module) => ({ default: module.OverviewPage })))
const MembersPage = lazy(() => import('./features/members/MembersPage').then((module) => ({ default: module.MembersPage })))
const ConsentsPage = lazy(() => import('./features/consents/ConsentsPage').then((module) => ({ default: module.ConsentsPage })))
const MerchantPage = lazy(() => import('./features/merchant/MerchantPage').then((module) => ({ default: module.MerchantPage })))
const ProductsPage = lazy(() => import('./features/products/ProductsPage').then((module) => ({ default: module.ProductsPage })))
const ProductDetailPage = lazy(() => import('./features/products/ProductDetailPage').then((module) => ({ default: module.ProductDetailPage })))
const InventoryPage = lazy(() => import('./features/inventory/InventoryPage').then((module) => ({ default: module.InventoryPage })))
const OrdersPage = lazy(() => import('./features/orders/OrdersPage').then((module) => ({ default: module.OrdersPage })))
const OrderDetailPage = lazy(() => import('./features/orders/OrderDetailPage').then((module) => ({ default: module.OrderDetailPage })))
const PaymentsPage = lazy(() => import('./features/payments/PaymentsPage').then((module) => ({ default: module.PaymentsPage })))
const PaymentDetailPage = lazy(() => import('./features/payments/PaymentDetailPage').then((module) => ({ default: module.PaymentDetailPage })))
const ReceiptsPage = lazy(() => import('./features/receipts/ReceiptsPage').then((module) => ({ default: module.ReceiptsPage })))
const ReceiptDetailPage = lazy(() => import('./features/receipts/ReceiptDetailPage').then((module) => ({ default: module.ReceiptDetailPage })))
const QuotesPage = lazy(() => import('./features/quotes/QuotesPage').then((module) => ({ default: module.QuotesPage })))
const QuoteDetailPage = lazy(() => import('./features/quotes/QuoteDetailPage').then((module) => ({ default: module.QuoteDetailPage })))
const RefundsPage = lazy(() => import('./features/refunds/RefundsPage').then((module) => ({ default: module.RefundsPage })))
const RefundDetailPage = lazy(() => import('./features/refunds/RefundDetailPage').then((module) => ({ default: module.RefundDetailPage })))
const PaymentChannelsPage = lazy(() => import('./features/channels/PaymentChannelsPage').then((module) => ({ default: module.PaymentChannelsPage })))
const ReturnsPage = lazy(() => import('./features/returns/ReturnsPage').then((module) => ({ default: module.ReturnsPage })))
const FulfillmentPage = lazy(() => import('./features/fulfillment/FulfillmentPage').then((module) => ({ default: module.FulfillmentPage })))
const JobsPage = lazy(() => import('./features/jobs/JobsPage').then((module) => ({ default: module.JobsPage })))
const JobDetailPage = lazy(() => import('./features/jobs/JobDetailPage').then((module) => ({ default: module.JobDetailPage })))
const OutboxPage = lazy(() => import('./features/outbox/OutboxPage').then((module) => ({ default: module.OutboxPage })))
const ReconciliationPage = lazy(() => import('./features/reconciliation/ReconciliationPage').then((module) => ({ default: module.ReconciliationPage })))
const AdmissionPage = lazy(() => import('./features/admission/AdmissionPage').then((module) => ({ default: module.AdmissionPage })))
const OperationLogsPage = lazy(() => import('./features/operation-logs/OperationLogsPage').then((module) => ({ default: module.OperationLogsPage })))
const NotFoundPage = lazy(() => import('./features/system/NotFoundPage').then((module) => ({ default: module.NotFoundPage })))

function Admin({ children }: { children: ReactNode }) {
  return <RequireScope anyOf={[TRADE_ADMIN]}>{children}</RequireScope>
}

export default function App() {
  if (runtimeConfigError) {
    return (
      <div className="auth-error" role="alert">
        <h1>无法启动交易中枢</h1>
        <p>{runtimeConfigError.message}</p>
      </div>
    )
  }
  return (
    <Routes>
      <Route path="login" element={<LoginPage />} />
      <Route path="auth/callback" element={<AuthCallbackPage />} />
      <Route element={<RequireAuth><AppShell /></RequireAuth>}>
        <Route index element={<Admin><OverviewPage /></Admin>} />
        <Route path="members" element={<Admin><MembersPage /></Admin>} />
        <Route path="consents" element={<Admin><ConsentsPage /></Admin>} />
        <Route path="merchant" element={<Admin><MerchantPage /></Admin>} />
        <Route path="products" element={<Admin><ProductsPage /></Admin>} />
        <Route path="products/:id" element={<Admin><ProductDetailPage /></Admin>} />
        <Route path="inventory" element={<Admin><InventoryPage /></Admin>} />
        <Route path="orders" element={<Admin><OrdersPage /></Admin>} />
        <Route path="orders/:orderNo" element={<Admin><OrderDetailPage /></Admin>} />
        <Route path="payments" element={<Admin><PaymentsPage /></Admin>} />
        <Route path="payments/:paymentNo" element={<Admin><PaymentDetailPage /></Admin>} />
        <Route path="receipts" element={<Admin><ReceiptsPage /></Admin>} />
        <Route path="receipts/:receiptNo" element={<Admin><ReceiptDetailPage /></Admin>} />
        <Route path="quotes" element={<Admin><QuotesPage /></Admin>} />
        <Route path="quotes/:quoteId" element={<Admin><QuoteDetailPage /></Admin>} />
        <Route path="refunds" element={<Admin><RefundsPage /></Admin>} />
        <Route path="refunds/:refundNo" element={<Admin><RefundDetailPage /></Admin>} />
        <Route path="payment-channels" element={<Admin><PaymentChannelsPage /></Admin>} />
        <Route path="returns" element={<Admin><ReturnsPage /></Admin>} />
        <Route path="fulfillment" element={<Admin><FulfillmentPage /></Admin>} />
        <Route path="jobs" element={<Admin><RequireScope anyOf={[TRADE_ADMIN_JOBS_READ]}><JobsPage /></RequireScope></Admin>} />
        <Route path="jobs/:jobId" element={<Admin><RequireScope anyOf={[TRADE_ADMIN_JOBS_READ]}><JobDetailPage /></RequireScope></Admin>} />
        <Route path="outbox" element={<Admin><OutboxPage /></Admin>} />
        <Route path="reconciliation" element={<Admin><ReconciliationPage /></Admin>} />
        <Route path="admission" element={<Admin><AdmissionPage /></Admin>} />
        <Route path="operation-logs" element={<Admin><OperationLogsPage /></Admin>} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
