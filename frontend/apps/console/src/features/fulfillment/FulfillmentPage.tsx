import { useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { Button, ConfirmDialog, Field, PageHeader, Panel, StackForm, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { MutationBanner } from '../../shared/ui/QueryGate'
import { useAuth } from '../../shared/auth/useAuth'
import { TRADE_FULFILLMENT_WRITE } from '../../shared/auth/auth-context'

export function FulfillmentPage() {
  const auth = useAuth()
  const canWrite = auth.hasScope(TRADE_FULFILLMENT_WRITE)
  const [params] = useSearchParams()
  const [orderNo, setOrderNo] = useState(() => params.get('orderNo') ?? '')
  const [sourceRequestId, setSourceRequestId] = useState('')
  const [deliveryRef, setDeliveryRef] = useState('')
  const [confirmOpen, setConfirmOpen] = useState(false)
  const fulfill = useMutation({
    mutationFn: (payload: { orderNo: string; sourceRequestId: string; deliveryRef: string }) =>
      api.createFulfillment(payload.orderNo, { sourceRequestId: payload.sourceRequestId, deliveryRef: payload.deliveryRef }),
    onSuccess: () => setConfirmOpen(false),
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!canWrite) return
    setConfirmOpen(true)
  }

  return (
    <div className="workspace">
      <PageHeader
        title="履约"
        description="核对整单商品及交付凭证后，登记实际交付。"
      />
      {!canWrite ? <StateBanner tone="warn" title="当前令牌不能声明履约" detail="写入需要 trade.fulfillment.write。" /> : null}
      <StateBanner tone="warn" title="按订单号操作" detail="整单交付，不能用部分发货调用。可先到订单查询核对状态。" />
      <Panel>
        <h2>交付信息</h2>
        <MutationBanner error={fulfill.error} success={fulfill.data ? `履约单 ${fulfill.data.resourceNo} · ${fulfill.data.status}` : undefined} />
        <StackForm onSubmit={submit} actions={<Button tone="primary" type="submit" aria-haspopup="dialog" disabled={fulfill.isPending || !orderNo.trim() || !canWrite}>声明已交付</Button>}>
          <Field label="订单号" hint="可从订单详情带入。"><input value={orderNo} onChange={(event) => setOrderNo(event.target.value)} placeholder="ORD-…" required /></Field>
          <Field label="来源请求号"><input value={sourceRequestId} onChange={(event) => setSourceRequestId(event.target.value)} required /></Field>
          <Field label="交付凭证引用"><input value={deliveryRef} onChange={(event) => setDeliveryRef(event.target.value)} required /></Field>
        </StackForm>
        {orderNo.trim() ? <Link to={`/orders/${encodeURIComponent(orderNo.trim())}?section=fulfillment`}>核对订单与履约记录</Link> : null}
      </Panel>
      {confirmOpen ? (
        <ConfirmDialog
          title="确认整单已交付"
          description="这会声明整单全部明细已经实际交付，不是确认支付。"
          confirmLabel="确认交付"
          tone="primary"
          pending={fulfill.isPending}
          onClose={() => setConfirmOpen(false)}
          onConfirm={() => fulfill.mutate({
            orderNo: orderNo.trim(),
            sourceRequestId: sourceRequestId.trim(),
            deliveryRef: deliveryRef.trim(),
          })}
        >
          <MutationBanner error={fulfill.error} />
          <dl className="kv"><dt>订单号</dt><dd>{orderNo.trim()}</dd><dt>来源请求号</dt><dd>{sourceRequestId.trim()}</dd><dt>交付凭证</dt><dd>{deliveryRef.trim()}</dd></dl>
        </ConfirmDialog>
      ) : null}
    </div>
  )
}
