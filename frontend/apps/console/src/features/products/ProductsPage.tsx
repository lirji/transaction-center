import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { Badge, Button, CursorPager, Field, PageHeader, Panel, StackForm } from '../../components/ui'
import { api } from '../../shared/api/client'
import { MutationBanner, QueryGate } from '../../shared/ui/QueryGate'
import { formatMinor, parseInteger } from '../../shared/api/money'
import { parseCurrencyCode } from '../../shared/api/validate'

export function ProductsPage() {
  const [after, setAfter] = useState(0)
  const [productNo, setProductNo] = useState('')
  const [name, setName] = useState('')
  const [skuNo, setSkuNo] = useState('')
  const [unitPrice, setUnitPrice] = useState('')
  const [currency, setCurrency] = useState('CNY')
  const [formError, setFormError] = useState('')
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: ['products', after], queryFn: () => api.products(after) })
  const create = useMutation({
    mutationFn: api.createProduct,
    onSuccess: async () => {
      setAfter(0)
      setProductNo('')
      setName('')
      setSkuNo('')
      setUnitPrice('')
      await queryClient.invalidateQueries({ queryKey: ['products'] })
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    setFormError('')
    try {
      create.mutate({
        productNo: productNo.trim(),
        name: name.trim(),
        skuNo: skuNo.trim(),
        unitPrice: parseInteger(unitPrice, '单价', { min: 0 }),
        currency: parseCurrencyCode(currency),
      })
    } catch (cause) {
      setFormError(cause instanceof Error ? cause.message : '输入无效')
    }
  }

  return (
    <div className="workspace">
      <PageHeader
        eyebrow="Catalog"
        title="商品"
        description="列表价格是目录标价，不是可付金额。下单金额只能来自服务端报价。发布后快照不可改。"
      />
      <div className="page-grid">
        <Panel className="table-panel">
          <QueryGate query={query} emptyTitle="没有商品" emptyDetail="当前租户没有商品草稿或已发布记录。">
            {(rows) => (
              <>
                <table className="data-table">
                  <thead><tr><th>商品</th><th>状态</th><th>目录价</th><th>版本</th></tr></thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr key={row.productId}>
                        <td>
                          <Link to={`/products/${row.productId}`}><strong>{row.name}</strong></Link>
                          <small className="mono">{row.productId} / sku {row.skuId}</small>
                        </td>
                        <td><Badge tone={row.status === 'PUBLISHED' ? 'good' : row.status === 'OFFLINE' ? 'neutral' : 'warn'}>{row.status}</Badge></td>
                        <td>{formatMinor(row.unitPrice, row.currency)}<small className="mono">{row.currency} {row.unitPrice}</small></td>
                        <td className="mono">{row.version}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <CursorPager after={after} pageSize={20} lastId={rows.at(-1)?.productId} length={rows.length} onReset={() => setAfter(0)} onNext={setAfter} />
              </>
            )}
          </QueryGate>
        </Panel>
        <Panel>
          <h2>创建草稿</h2>
          <MutationBanner error={create.error} success={create.data ? `已创建 resourceId=${create.data.resourceId}` : undefined} />
          <StackForm onSubmit={submit} actions={<Button tone="primary" type="submit" disabled={create.isPending}>创建</Button>}>
            <Field label="productNo"><input value={productNo} onChange={(event) => setProductNo(event.target.value)} maxLength={64} required /></Field>
            <Field label="名称"><input value={name} onChange={(event) => setName(event.target.value)} maxLength={200} required /></Field>
            <Field label="首个 skuNo"><input value={skuNo} onChange={(event) => setSkuNo(event.target.value)} maxLength={64} required /></Field>
            <Field label="单价（最小单位整数）" error={formError} hint="提交值不会被展示层改写。">
              <input value={unitPrice} onChange={(event) => setUnitPrice(event.target.value)} inputMode="numeric" required />
            </Field>
            <Field label="币种"><input value={currency} onChange={(event) => setCurrency(event.target.value)} maxLength={3} required /></Field>
          </StackForm>
        </Panel>
      </div>
    </div>
  )
}
