import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { Badge, PageHeader, Panel, StateBanner } from '../../components/ui'
import { api, ApiProblem } from '../../shared/api/client'
import { useAuth } from '../../shared/auth/useAuth'
import {
  TRADE_ADMIN,
  TRADE_ADMISSION_WRITE,
  TRADE_ADMIN_EVIDENCE_READ,
  TRADE_ADMIN_JOBS_READ,
  TRADE_EVENTS_REPLAY,
  TRADE_FULFILLMENT_WRITE,
  TRADE_RECONCILIATION_QUERY,
  TRADE_RETURN_WRITE,
} from '../../shared/auth/auth-context'

function countLabel(rows: unknown[] | undefined, error: boolean, loading: boolean) {
  if (loading) return '读取中'
  if (error) return '无法读取'
  if (!rows) return '—'
  return rows.length >= 20 ? `${rows.length}+` : String(rows.length)
}

export function OverviewPage() {
  const auth = useAuth()
  const merchant = useQuery({ queryKey: ['merchant'], queryFn: api.merchant, retry: false })
  const pendingRefunds = useQuery({ queryKey: ['refunds', { status: 'PENDING_APPROVAL', after: 0 }], queryFn: () => api.refunds({ status: 'PENDING_APPROVAL', after: 0 }) })
  const reviewOutbox = useQuery({ queryKey: ['outbox', 'REVIEW', 0], queryFn: () => api.outboxPublications('REVIEW', 0) })
  const admission = useQuery({ queryKey: ['admission'], queryFn: api.admissionPolicy, retry: false })
  const missingMerchant = merchant.error instanceof ApiProblem && merchant.error.status === 404
  const extra = [
    { scope: TRADE_EVENTS_REPLAY, label: 'Outbox 重放' },
    { scope: TRADE_RECONCILIATION_QUERY, label: '对账只查单' },
    { scope: TRADE_ADMISSION_WRITE, label: '改准入策略' },
    { scope: TRADE_FULFILLMENT_WRITE, label: '声明履约' },
    { scope: TRADE_RETURN_WRITE, label: '退货申请/验收' },
    { scope: TRADE_ADMIN_EVIDENCE_READ, label: '交易证据 / 资格评估' },
    { scope: TRADE_ADMIN_JOBS_READ, label: '任务租约观察' },
  ]

  return (
    <div className="workspace">
      <PageHeader
        eyebrow="Overview"
        title="交易运营工作台"
        description="本台只调用 OpenAPI 已交付的管理接口。可按编号与状态查询本租户订单，没有真实付款跳转、不能在此批准退款。会员商城与 C 端下单不在此控制台。"
      />
      <div className="metric-grid">
        <article className="metric-card">
          <p>JWT 租户</p>
          <strong className="mono">{auth.tenantId || '未声明'}</strong>
          <span>{/^[1-9][0-9]*$/.test(auth.tenantId) ? '正整数文本 tenant_id' : '后端会拒绝非正整数租户声明'}</span>
        </article>
        <article className="metric-card">
          <p>管理权限</p>
          <strong>{auth.hasScope(TRADE_ADMIN) ? 'trade.admin' : '缺失'}</strong>
          <span>演示数据需要身份匹配租户 900001</span>
        </article>
        <article className="metric-card">
          <p>Audience</p>
          <strong className="mono">{auth.audience.join(', ') || '令牌未带 aud'}</strong>
          <span>必须匹配 transaction-center</span>
        </article>
      </div>
      {merchant.isError && !missingMerchant ? <StateBanner tone="error" title="无法读取商户" detail={merchant.error instanceof Error ? merchant.error.message : '读取失败'} /> : null}
      <div className="page-grid">
        <Panel>
          <h2>本租户商户</h2>
          {merchant.isLoading ? <StateBanner tone="loading" title="正在读取" detail="GET /admin/merchant" /> : null}
          {missingMerchant ? (
            <p>尚未配置逻辑商户。下单前请先到 <Link to="/merchant">商户</Link> 创建，不要把商品标价当成可付金额。</p>
          ) : merchant.data ? (
            <dl className="kv">
              <dt>商户号</dt><dd className="mono">{merchant.data.merchantNo}</dd>
              <dt>名称</dt><dd>{merchant.data.name}</dd>
              <dt>状态</dt><dd><Badge tone={merchant.data.status === 'ACTIVE' ? 'good' : 'danger'}>{merchant.data.status}</Badge></dd>
              <dt>版本</dt><dd className="mono">{merchant.data.version}</dd>
            </dl>
          ) : null}
        </Panel>
        <Panel>
          <h2>待处理</h2>
          <dl className="kv">
            <dt><Link to="/orders">订单查询</Link></dt>
            <dd>按编号 / 状态 / 会员</dd>
            <dt><Link to="/refunds">待审批退款</Link></dt>
            <dd>{countLabel(pendingRefunds.data, pendingRefunds.isError, pendingRefunds.isLoading)}</dd>
            <dt><Link to="/outbox">Outbox REVIEW</Link></dt>
            <dd>{countLabel(reviewOutbox.data, reviewOutbox.isError, reviewOutbox.isLoading)}</dd>
            <dt><Link to="/admission">准入策略</Link></dt>
            <dd>
              {admission.isLoading
                ? '读取中'
                : admission.data
                  ? `下单 ${admission.data.orderEnabled ? '允许' : '关闭'} · 支付 ${admission.data.paymentEnabled ? '允许' : '关闭'}`
                  : '无法读取'}
            </dd>
          </dl>
        </Panel>
        <Panel>
          <h2>额外 scope</h2>
          <p>下列操作除 trade.admin 外还需要独立 scope；证据与任务默认不出现在导航。</p>
          <ul>
            {extra.map((item) => (
              <li key={item.scope}>
                {item.label} · <code>{item.scope}</code> · {auth.hasScope(item.scope) ? '当前令牌具备' : '当前令牌不具备'}
              </li>
            ))}
          </ul>
        </Panel>
      </div>
    </div>
  )
}
