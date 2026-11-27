import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button, ConfirmDialog, Field, PageHeader, Panel, StackForm, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { MutationBanner } from '../../shared/ui/QueryGate'
import { invalidateIfVersionConflict } from '../../shared/ui/invalidateIfVersionConflict'
import { describeProblem } from '../../shared/api/client'
import { useAuth } from '../../shared/auth/useAuth'
import { TRADE_ADMISSION_WRITE } from '../../shared/auth/auth-context'

export function AdmissionPage() {
  const auth = useAuth()
  const canWrite = auth.hasScope(TRADE_ADMISSION_WRITE)
  const [orderEnabled, setOrderEnabled] = useState<boolean>()
  const [paymentEnabled, setPaymentEnabled] = useState<boolean>()
  const [reason, setReason] = useState('')
  const [confirmDisable, setConfirmDisable] = useState(false)
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: ['admission'], queryFn: api.admissionPolicy })
  const update = useMutation({
    mutationFn: (payload: { version: number; orderEnabled: boolean; paymentEnabled: boolean; reason: string }) =>
      api.updateAdmissionPolicy(payload.version, { orderEnabled: payload.orderEnabled, paymentEnabled: payload.paymentEnabled, reason: payload.reason }),
    onSuccess: async () => {
      setConfirmDisable(false)
      await queryClient.invalidateQueries({ queryKey: ['admission'] })
    },
    onError: (error) => void invalidateIfVersionConflict(queryClient, error, ['admission']),
  })

  const nextOrder = orderEnabled ?? query.data?.orderEnabled ?? true
  const nextPayment = paymentEnabled ?? query.data?.paymentEnabled ?? true
  const disabling = Boolean(query.data && ((query.data.orderEnabled && !nextOrder) || (query.data.paymentEnabled && !nextPayment)))

  function payload() {
    if (!query.data) return undefined
    return {
      version: query.data.version,
      orderEnabled: nextOrder,
      paymentEnabled: nextPayment,
      reason: reason.trim(),
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const body = payload()
    if (!body) return
    if (disabling) {
      setConfirmDisable(true)
      return
    }
    update.mutate(body)
  }

  return (
    <div className="workspace">
      <PageHeader
        title="准入策略"
        description="关闭只影响新增受理，不会取消既有恢复。写入同时需要 trade.admin 与 trade.admission.write。"
      />
      {!canWrite ? <StateBanner tone="warn" title="当前令牌不能改策略" detail="仍可读取。保存需要 trade.admission.write。" /> : null}
      <MutationBanner error={update.error} success={update.data ? `已保存 version=${update.data.version}` : undefined} />
      <Panel>
        <h2>订单与支付受理</h2>
        {query.isLoading ? <StateBanner tone="loading" title="正在读取" detail="GET /admin/admission-policy" /> : null}
        {query.isError ? <StateBanner tone="error" title={describeProblem(query.error).title} detail={describeProblem(query.error).detail} /> : null}
        {query.data ? (
          <StackForm onSubmit={submit} actions={<Button tone="primary" type="submit" disabled={!canWrite || update.isPending || !reason.trim()}>保存</Button>}>
            <dl className="summary-fields">
              <div><dt>当前新订单受理</dt><dd>{query.data.orderEnabled ? '开放' : '暂停'}</dd></div>
              <div><dt>当前新支付受理</dt><dd>{query.data.paymentEnabled ? '开放' : '暂停'}</dd></div>
              <div><dt>策略版本</dt><dd>{query.data.version}</dd></div>
            </dl>
            <Field label="新订单受理">
              <select value={String(orderEnabled ?? query.data.orderEnabled)} onChange={(event) => setOrderEnabled(event.target.value === 'true')}>
                <option value="true">允许新订单</option>
                <option value="false">关闭新订单</option>
              </select>
            </Field>
            <Field label="新支付受理">
              <select value={String(paymentEnabled ?? query.data.paymentEnabled)} onChange={(event) => setPaymentEnabled(event.target.value === 'true')}>
                <option value="true">允许新支付</option>
                <option value="false">关闭新支付</option>
              </select>
            </Field>
            <Field label="原因" wide><input value={reason} onChange={(event) => setReason(event.target.value)} required /></Field>
          </StackForm>
        ) : null}
      </Panel>
      {confirmDisable ? (
        <ConfirmDialog
          title="关闭新增受理"
          description="关闭只影响新订单或新支付，不会取消既有恢复任务。"
          confirmLabel="确认关闭"
          pending={update.isPending}
          onClose={() => setConfirmDisable(false)}
          onConfirm={() => {
            const body = payload()
            if (body) update.mutate(body)
          }}
        >
          <MutationBanner error={update.error} />
          <p>下单 {nextOrder ? '允许' : '关闭'} · 支付 {nextPayment ? '允许' : '关闭'}</p>
        </ConfirmDialog>
      ) : null}
    </div>
  )
}
