import { ProductImageEditor, ProductOperations, ProductThumbnail, PRODUCT_BATCH_LIMIT, type ProductSelection } from './ProductOperations'
import { NumberedPagePager } from '../../components/NumberedPager'
import { useNumberedList, pageItems } from '../../shared/ui/useNumberedList'
import { appliedFilters } from '../../shared/ui/appliedFilters'
import { useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { Link, useLocation } from 'react-router-dom'
import { Badge, Button, DataTable, Field, Modal, PageHeader, Panel, CopyValue } from '../../components/ui'
import { api } from '../../shared/api/client'
import { MutationBanner, QueryGate } from '../../shared/ui/QueryGate'
import { formatMinorAmount, parseInteger } from '../../shared/api/money'
import { parseCurrencyCode } from '../../shared/api/validate'

import { EmptySelect, FilterBar } from '../../shared/ui/FilterBar'
import { useAdminListSearch } from '../../shared/ui/useAdminListSearch'
import { productStatusSchema, adminProductSchema, type AdminProduct } from '../../shared/api/schemas'
import { productStatusLabels } from './productDisplay'

export function ProductsPage() {
  const { search } = useLocation()
  const { params, after, page, setPage, apply, remove, setAfter, pageSize, setPageSize } = useAdminListSearch()
  const filters = {
    productNo: params.get('productNo') || undefined,
    skuNo: params.get('skuNo') || undefined,
    name: params.get('name') || undefined,
    status: params.get('status') || undefined,
  }
  const selectionScope = JSON.stringify(filters)
  const [selection, setSelection] = useState<{ scope: string; items: ProductSelection[] }>({ scope: selectionScope, items: [] })
  const selected = selection.scope === selectionScope ? selection.items : []
  const [imageProduct, setImageProduct] = useState<AdminProduct>()
  const clearSelection = () => setSelection({ scope: selectionScope, items: [] })
  function toggleSelection(row: AdminProduct) {
    const previous = selected.some((item) => item.productId === row.productId)
    const items = previous ? selected.filter((item) => item.productId !== row.productId)
      : [...selected, { productId: row.productId, version: row.version }]
    if (items.length <= PRODUCT_BATCH_LIMIT) setSelection({ scope: selectionScope, items })
  }
  const [productNoFilter, setProductNoFilter] = useState(filters.productNo ?? '')
  const [skuNoFilter, setSkuNoFilter] = useState(filters.skuNo ?? '')
  const [nameFilter, setNameFilter] = useState(filters.name ?? '')
  const [statusFilter, setStatusFilter] = useState(filters.status ?? '')
  const [createOpen, setCreateOpen] = useState(false)
  const [productNo, setProductNo] = useState('')
  const [name, setName] = useState('')
  const [skuNo, setSkuNo] = useState('')
  const [unitPrice, setUnitPrice] = useState('')
  const [currency, setCurrency] = useState('CNY')
  const [formError, setFormError] = useState('')
  const queryClient = useQueryClient()
  const query = useNumberedList({ queryKey: ['products', after, pageSize, filters], queryFn: () => api.searchProducts({ after, limit: pageSize, ...filters }) }, { path: 'numbered-pages/products', schema: adminProductSchema, after: after, page: page, pageSize: pageSize, filters: filters }, pageItems)
  const create = useMutation({
    mutationFn: api.createProduct,
    onSuccess: async () => {
      setCreateOpen(false)
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

  function clearFilters() {
    setProductNoFilter('')
    setSkuNoFilter('')
    setNameFilter('')
    setStatusFilter('')
    apply({})
  }

  function openCreate() {
    setFormError('')
    create.reset()
    setCreateOpen(true)
  }

  return (
    <div className="workspace">
      <PageHeader
        title="商品"
        description="查询目录价和发布状态，维护商品与 SKU。"
        actions={<><ProductOperations selected={selected} clear={clearSelection} resetPage={() => setAfter('0')} />
          <Button tone="primary" aria-haspopup="dialog" onClick={openCreate}><Plus size={16} aria-hidden="true" />创建草稿</Button></>}
      />
      <FilterBar applied={appliedFilters(params, remove, [
          ['商品编号', 'productNo', () => setProductNoFilter('')],
          ['SKU 编码', 'skuNo', () => setSkuNoFilter('')],
          ['商品名称', 'name', () => setNameFilter('')],
          ['发布状态', 'status', () => setStatusFilter(''), (value) => productStatusLabels[value as keyof typeof productStatusLabels] ?? value],
        ])}
          onReset={clearFilters} onSubmit={() => apply({
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
      {create.data && <MutationBanner error={null} success={`商品草稿 ${create.data.resourceId} 已创建。`} />}
      <Panel className="table-panel">
        <h2 className="table-title">商品列表</h2>
        <QueryGate query={query} emptyTitle="没有商品" emptyDetail="当前条件没有匹配的商品。">
          {(rows) => (
            <>
              <DataTable label="商品列表" className="trade-list-table pin-key">
                <thead><tr><th className="key-cell">商品</th><th>发布状态</th><th className="numeric-cell">目录价</th><th>币种</th><th>主 SKU ID</th><th>商品 ID</th><th>发布修订</th><th>版本</th><th>操作</th></tr></thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.productId}>
                      <td>
                        <div className="product-identity">
                          <input type="checkbox" aria-label={`选择 ${row.name}`} checked={selected.some((item) => item.productId === row.productId)}
                            disabled={selected.length === PRODUCT_BATCH_LIMIT && !selected.some((item) => item.productId === row.productId)}
                            onChange={() => toggleSelection(row)} />
                          <ProductThumbnail url={row.imageUrl} />
                          <div><Link to={`/products/${row.productId}`} state={{ listSearch: search }}>{row.name}</Link>
                            <small><CopyValue value={row.productNo} name="商品编号" /></small></div>
                        </div>
                      </td>
                      <td><Badge tone={row.status === productStatusSchema.enum.PUBLISHED ? 'good' : row.status === productStatusSchema.enum.OFFLINE ? 'neutral' : 'warn'}>{productStatusLabels[row.status]}</Badge></td>
                      <td className="numeric-cell">{formatMinorAmount(row.unitPrice)}</td>
                      <td>{row.currency}</td>
                      <td className="mono">{row.skuId}</td>
                      <td><CopyValue value={row.productId} name="商品 ID" /></td>
                      <td>{row.publishedRevision ?? '—'}</td>
                      <td className="mono">{row.version}</td>
                      <td><Button tone="ghost" aria-haspopup="dialog" onClick={() => setImageProduct(row)}>编辑展示图</Button></td>
                    </tr>
                  ))}
                </tbody>
              </DataTable>
            </>
          )}
        </QueryGate>
        <NumberedPagePager numbered={query.numbered} onNumber={setPage} scope={['products', filters]} after={after} onPage={setAfter} pageSize={pageSize} onPageSize={setPageSize}
          result={query.isSuccess ? { items: query.data, nextAfter: query.data.at(-1)?.productId ?? null, hasMore: query.data.length === pageSize } : undefined} />
      </Panel>
      {imageProduct && <ProductImageEditor product={imageProduct} close={() => setImageProduct(undefined)} />}
      {createOpen && (
        <Modal title="创建商品草稿" description="填写商品信息和主 SKU。创建后可在商品详情维护规格并发布。"
          pending={create.isPending} onClose={() => setCreateOpen(false)}>
          <form className="modal-form-grid" onSubmit={submit}>
            <MutationBanner error={create.error} />
            <Field label="商品编号"><input data-initial-focus value={productNo} onChange={(event) => setProductNo(event.target.value)} maxLength={64} required /></Field>
            <Field label="商品名称"><input value={name} onChange={(event) => setName(event.target.value)} maxLength={200} required /></Field>
            <Field label="主 SKU 编码"><input value={skuNo} onChange={(event) => setSkuNo(event.target.value)} maxLength={64} required /></Field>
            <Field label="单价（最小单位整数）" error={formError} hint="例如 199 分表示 1.99 元。">
              <input value={unitPrice} onChange={(event) => setUnitPrice(event.target.value)} inputMode="numeric" required />
            </Field>
            <Field label="币种"><input value={currency} onChange={(event) => setCurrency(event.target.value)} maxLength={3} required /></Field>
            <footer className="modal-actions">
              <Button onClick={() => setCreateOpen(false)} disabled={create.isPending}>取消</Button>
              <Button tone="primary" type="submit" loading={create.isPending}>{create.isPending ? '正在创建…' : '创建'}</Button>
            </footer>
          </form>
        </Modal>
      )}
    </div>
  )
}
