import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { Badge, Button, Field, PageHeader, PagePager, Panel, StackForm } from '../../components/ui'
import { api } from '../../shared/api/client'
import { MutationBanner, QueryGate } from '../../shared/ui/QueryGate'
import { formatMinorAmount, parseInteger } from '../../shared/api/money'
import { parseCurrencyCode } from '../../shared/api/validate'

import { EmptySelect, FilterBar } from '../../shared/ui/FilterBar'
import { useAdminListSearch } from '../../shared/ui/useAdminListSearch'
import { productStatusSchema } from '../../shared/api/schemas'
import { productStatusLabels } from './productDisplay'

export function ProductsPage() {
  const { params, after, apply, setAfter } = useAdminListSearch()
  const filters = {
    productNo: params.get('productNo') || undefined,
    skuNo: params.get('skuNo') || undefined,
    name: params.get('name') || undefined,
    status: params.get('status') || undefined,
  }
  const [productNoFilter, setProductNoFilter] = useState(filters.productNo ?? '')
  const [skuNoFilter, setSkuNoFilter] = useState(filters.skuNo ?? '')
  const [nameFilter, setNameFilter] = useState(filters.name ?? '')
  const [statusFilter, setStatusFilter] = useState(filters.status ?? '')
  const [productNo, setProductNo] = useState('')
  const [name, setName] = useState('')
  const [skuNo, setSkuNo] = useState('')
  const [unitPrice, setUnitPrice] = useState('')
  const [currency, setCurrency] = useState('CNY')
  const [formError, setFormError] = useState('')
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: ['products', after, filters], queryFn: () => api.searchProducts({ after, ...filters }) })
  const create = useMutation({
    mutationFn: api.createProduct,
    onSuccess: async () => {
      setAfter('0')
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
        description="查询商品目录价和发布状态。在商品详情查看 SKU、草稿及发布修订。"
      />
      <FilterBar onSubmit={() => apply({
        productNo: productNoFilter.trim(), skuNo: skuNoFilter.trim(), name: nameFilter.trim(), status: statusFilter,
      })}>
        <Field label="按商品编号"><input value={productNoFilter} onChange={(event) => setProductNoFilter(event.target.value)} maxLength={64} /></Field>
        <Field label="按 SKU 编码"><input value={skuNoFilter} onChange={(event) => setSkuNoFilter(event.target.value)} maxLength={64} /></Field>
        <Field label="按商品名称（精确）"><input value={nameFilter} onChange={(event) => setNameFilter(event.target.value)} maxLength={200} /></Field>
        <Field label="发布状态">
          <EmptySelect value={statusFilter} onChange={setStatusFilter} options={productStatusSchema.options}
            optionLabel={(value) => productStatusLabels[productStatusSchema.parse(value)]} />
        </Field>
      </FilterBar>
      <div className="section-gap">
        <Panel className="table-panel">
          <QueryGate query={query} emptyTitle="没有商品" emptyDetail="当前条件没有匹配的商品。">
            {(rows) => (
              <>
                <table className="data-table trade-list-table">
                  <thead><tr><th>商品名称</th><th>商品编号</th><th>商品 ID</th><th>主 SKU ID</th><th>发布状态</th><th>目录价</th><th>币种</th><th>发布修订</th><th>版本</th></tr></thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr key={row.productId}>
                        <td>
                          <Link to={`/products/${row.productId}`}><strong>{row.name}</strong></Link>
                        </td>
                        <td className="mono">{row.productNo}</td>
                        <td className="mono">{row.productId}</td>
                        <td className="mono">{row.skuId}</td>
                        <td><Badge tone={row.status === productStatusSchema.enum.PUBLISHED ? 'good' : row.status === productStatusSchema.enum.OFFLINE ? 'neutral' : 'warn'}>{productStatusLabels[row.status]}</Badge></td>
                        <td>{formatMinorAmount(row.unitPrice)}</td>
                        <td>{row.currency}</td>
                        <td>{row.publishedRevision ?? '—'}</td>
                        <td className="mono">{row.version}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <PagePager after={after} nextAfter={rows.at(-1)?.productId ?? null} hasMore={rows.length === 20} onReset={() => setAfter('0')} onNext={setAfter} />
              </>
            )}
          </QueryGate>
        </Panel>
        <Panel>
          <h2>创建草稿</h2>
          <MutationBanner error={create.error} success={create.data ? `已创建 resourceId=${create.data.resourceId}` : undefined} />
          <StackForm onSubmit={submit} actions={<Button tone="primary" type="submit" disabled={create.isPending}>创建</Button>}>
            <Field label="商品编号"><input value={productNo} onChange={(event) => setProductNo(event.target.value)} maxLength={64} required /></Field>
            <Field label="商品名称"><input value={name} onChange={(event) => setName(event.target.value)} maxLength={200} required /></Field>
            <Field label="主 SKU 编码"><input value={skuNo} onChange={(event) => setSkuNo(event.target.value)} maxLength={64} required /></Field>
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
