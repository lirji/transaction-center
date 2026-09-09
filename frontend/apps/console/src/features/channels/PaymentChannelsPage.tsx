import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Badge, Button, CursorPager, Field, PageHeader, Panel, StackForm, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { MutationBanner, QueryGate } from '../../shared/ui/QueryGate'
import { parseChannelCode, parseCurrencyCode, parseHttpsUrl } from '../../shared/api/validate'

export function PaymentChannelsPage() {
  const [after, setAfter] = useState(0)
  const [channelCode, setChannelCode] = useState('')
  const [channelMerchantNo, setChannelMerchantNo] = useState('')
  const [currency, setCurrency] = useState('CNY')
  const [credentialRef, setCredentialRef] = useState('')
  const [returnUrlId, setReturnUrlId] = useState('')
  const [returnUrl, setReturnUrl] = useState('')
  const [formError, setFormError] = useState('')
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: ['payment-channels', after], queryFn: () => api.paymentChannels(after) })
  const create = useMutation({
    mutationFn: api.createPaymentChannel,
    onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: ['payment-channels'] }) },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    setFormError('')
    try {
      create.mutate({
        channelCode: parseChannelCode(channelCode),
        channelMerchantNo: channelMerchantNo.trim(),
        currency: parseCurrencyCode(currency),
        credentialRef: credentialRef.trim(),
        returnUrlId: returnUrlId.trim(),
        returnUrl: parseHttpsUrl(returnUrl, 'returnUrl'),
      })
    } catch (cause) {
      setFormError(cause instanceof Error ? cause.message : '输入无效')
    }
  }

  return (
    <div className="workspace">
      <PageHeader
        eyebrow="Payments"
        title="支付渠道"
        description="列表不回显密钥或完整 returnUrl。登记渠道不等于真实渠道已执行；会员侧 202 只表示本地受理。"
      />
      <StateBanner tone="info" title="不会构造付款跳转" detail="本台没有支付跳转按钮，也不把渠道登记当成收款成功。" />
      <div className="page-grid">
        <Panel className="table-panel">
          <QueryGate query={query} emptyTitle="没有渠道" emptyDetail="尚未登记支付渠道。">
            {(rows) => (
              <>
                <table className="data-table">
                  <thead><tr><th>渠道</th><th>商户号</th><th>币种</th><th>状态</th></tr></thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr key={row.channelId}>
                        <td><strong>{row.channelCode}</strong><small className="mono">{row.channelId} / {row.returnUrlId}</small></td>
                        <td className="mono">{row.channelMerchantNo}</td>
                        <td>{row.currency}</td>
                        <td><Badge tone={row.status === 'ACTIVE' ? 'good' : 'danger'}>{row.status}</Badge></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <CursorPager after={after} pageSize={20} lastId={rows.at(-1)?.channelId} length={rows.length} onReset={() => setAfter(0)} onNext={setAfter} />
              </>
            )}
          </QueryGate>
        </Panel>
        <Panel>
          <h2>登记渠道</h2>
          <MutationBanner error={create.error} success={create.data ? `resourceId=${create.data.resourceId}` : undefined} />
          <StackForm onSubmit={submit} actions={<Button tone="primary" type="submit" disabled={create.isPending}>登记</Button>}>
            <Field label="channelCode" error={formError} hint="须符合 ^[A-Z][A-Z0-9_]{0,63}$">
              <input value={channelCode} onChange={(event) => setChannelCode(event.target.value)} required />
            </Field>
            <Field label="channelMerchantNo"><input value={channelMerchantNo} onChange={(event) => setChannelMerchantNo(event.target.value)} required /></Field>
            <Field label="currency"><input value={currency} onChange={(event) => setCurrency(event.target.value)} required /></Field>
            <Field label="credentialRef" hint="只提交引用，不会在列表回显。">
              <input value={credentialRef} onChange={(event) => setCredentialRef(event.target.value)} type="password" required />
            </Field>
            <Field label="returnUrlId"><input value={returnUrlId} onChange={(event) => setReturnUrlId(event.target.value)} required /></Field>
            <Field label="returnUrl"><input value={returnUrl} onChange={(event) => setReturnUrl(event.target.value)} required /></Field>
          </StackForm>
        </Panel>
      </div>
    </div>
  )
}
