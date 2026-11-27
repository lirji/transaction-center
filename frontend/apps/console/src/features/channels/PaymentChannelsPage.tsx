import { NumberedCursorPager } from '../../components/NumberedPager'
import { channelViewSchema } from '../../shared/api/schemas'
import { useLocalListPage, useNumberedList, pageItems } from '../../shared/ui/useNumberedList'
import { usePageSize } from '../../shared/ui/usePageSize'
import { useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Badge, Button, Field, PageHeader, Panel, StackForm, StateBanner, DataTable } from '../../components/ui'
import { api } from '../../shared/api/client'
import { MutationBanner, QueryGate } from '../../shared/ui/QueryGate'
import { parseChannelCode, parseCurrencyCode, parseHttpsUrl } from '../../shared/api/validate'

export function PaymentChannelsPage() {
  const [after, setAfter, page, setPage] = useLocalListPage<number>(0)
  const [pageSize, setPageSize] = usePageSize('payment-channels', 20, () => setAfter(0))
  const [channelCode, setChannelCode] = useState('')
  const [channelMerchantNo, setChannelMerchantNo] = useState('')
  const [currency, setCurrency] = useState('CNY')
  const [credentialRef, setCredentialRef] = useState('')
  const [returnUrlId, setReturnUrlId] = useState('')
  const [returnUrl, setReturnUrl] = useState('')
  const [formError, setFormError] = useState('')
  const queryClient = useQueryClient()
  const query = useNumberedList({ queryKey: ['payment-channels', after, pageSize], queryFn: () => api.paymentChannels(after, pageSize) }, { path: 'numbered-pages/payment-channels', schema: channelViewSchema, after: after, page: page, pageSize: pageSize }, pageItems)
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
        title="支付渠道"
        description="列表不回显密钥或完整 returnUrl。登记渠道不等于真实渠道已执行；会员侧 202 只表示本地受理。"
      />
      <StateBanner tone="info" title="不会构造付款跳转" detail="本台没有支付跳转按钮，也不把渠道登记当成收款成功。" />
      <div className="section-gap">
        <Panel className="table-panel">
          <h2 className="table-title">渠道绑定</h2>
          <QueryGate query={query} emptyTitle="没有渠道" emptyDetail="尚未登记支付渠道。">
            {(rows) => (
              <>
                <DataTable label="支付渠道列表" className="trade-list-table pin-key">
                  <thead><tr><th>渠道编码</th><th>渠道 ID</th><th>逻辑商户号</th><th>渠道商户号</th><th>币种</th><th>回跳配置编号</th><th>绑定状态</th></tr></thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr key={row.channelId}>
                        <td>{row.channelCode}</td>
                        <td className="mono">{row.channelId}</td>
                        <td className="mono">{row.merchantNo}</td>
                        <td className="mono">{row.channelMerchantNo}</td>
                        <td>{row.currency}</td>
                        <td className="mono">{row.returnUrlId}</td>
                        <td><Badge tone={row.status === 'ACTIVE' ? 'good' : 'danger'}>{row.status}</Badge></td>
                      </tr>
                    ))}
                  </tbody>
                </DataTable>
              </>
            )}
          </QueryGate>
          <NumberedCursorPager numbered={query.numbered} onNumber={setPage} scope={['payment-channels']} after={after} onPage={setAfter} pageSize={pageSize} onPageSize={setPageSize}
            rows={query.isSuccess ? query.data : undefined} getCursor={(row) => row.channelId} />
        </Panel>
        <Panel>
          <h2>登记渠道</h2>
          <MutationBanner error={create.error} success={create.data ? `resourceId=${create.data.resourceId}` : undefined} />
          <StackForm onSubmit={submit} actions={<Button tone="primary" type="submit" loading={create.isPending}>登记</Button>}>
            <Field label="渠道编码" error={formError} hint="须符合 ^[A-Z][A-Z0-9_]{0,63}$">
              <input value={channelCode} onChange={(event) => setChannelCode(event.target.value)} required />
            </Field>
            <Field label="渠道商户号"><input value={channelMerchantNo} onChange={(event) => setChannelMerchantNo(event.target.value)} required /></Field>
            <Field label="币种"><input value={currency} onChange={(event) => setCurrency(event.target.value)} required /></Field>
            <Field label="凭据引用" hint="只提交引用，不会在列表回显。">
              <input value={credentialRef} onChange={(event) => setCredentialRef(event.target.value)} type="password" required />
            </Field>
            <Field label="回跳配置编号"><input value={returnUrlId} onChange={(event) => setReturnUrlId(event.target.value)} required /></Field>
            <Field label="回跳 HTTPS 地址"><input value={returnUrl} onChange={(event) => setReturnUrl(event.target.value)} required /></Field>
          </StackForm>
        </Panel>
      </div>
    </div>
  )
}
