import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useLocation, useParams } from 'react-router-dom'
import { Badge, Button, ConfirmDialog, CursorPager, DataTable, Field, Modal, PageHeader, Panel, StackForm, StateBanner } from '../../components/ui'
import { api, type Sku } from '../../shared/api/client'
import { MutationBanner } from '../../shared/ui/QueryGate'
import { invalidateIfVersionConflict } from '../../shared/ui/invalidateIfVersionConflict'
import { formatMinor, formatMinorAmount, parseInteger } from '../../shared/api/money'
import { describeProblem } from '../../shared/api/client'
import { parseCurrencyCode } from '../../shared/api/validate'
import { productStatusSchema } from '../../shared/api/schemas'
import { productStatusLabels, stockTypeLabels } from './productDisplay'

export function ProductDetailPage() {
  const { id = '' } = useParams()
  return <ProductDetail key={id} id={id} />
}

function ProductDetail({ id }: { id: string }) {
  const queryClient = useQueryClient()
  const location = useLocation()
  const list = { pathname: '/products', search: typeof location.state?.listSearch === 'string' ? location.state.listSearch : '' }
  const product = useQuery({ queryKey: ['product', id], queryFn: () => api.product(id), enabled: Boolean(id) })
  const [skuAfter, setSkuAfter] = useState(0)
  const skus = useQuery({ queryKey: ['product-skus', id, skuAfter], queryFn: () => api.productSkus(id, skuAfter), enabled: Boolean(id) })
  const [name, setName] = useState('')
  const [unitPrice, setUnitPrice] = useState('')
  const [currency, setCurrency] = useState('')
  const [skuNo, setSkuNo] = useState('')
  const [skuPrice, setSkuPrice] = useState('')
  const [skuCurrency, setSkuCurrency] = useState('')
  const [stockType, setStockType] = useState<'PHYSICAL' | 'SERVICE'>('PHYSICAL')
  const [specKey, setSpecKey] = useState('color')
  const [specValue, setSpecValue] = useState('')
  const [skuAddError, setSkuAddError] = useState('')
  const [formError, setFormError] = useState('')
  const [confirm, setConfirm] = useState<'publish' | 'unpublish'>()
  const [editing, setEditing] = useState<Sku>()
  const [skuEditError, setSkuEditError] = useState('')
  const [editPrice, setEditPrice] = useState('')
  const [editCurrency, setEditCurrency] = useState('CNY')
  const [editStockType, setEditStockType] = useState<'PHYSICAL' | 'SERVICE'>('PHYSICAL')
  const [editSpecKey, setEditSpecKey] = useState('')
  const [editSpecValue, setEditSpecValue] = useState('')

  const invalidate = async () => {
    await queryClient.invalidateQueries({ queryKey: ['product', id] })
    await queryClient.invalidateQueries({ queryKey: ['product-skus', id] })
    await queryClient.invalidateQueries({ queryKey: ['products'] })
  }
  const onConflict = (error: unknown) => void invalidateIfVersionConflict(queryClient, error, ['product', id])
  const edit = useMutation({
    mutationFn: (body: { expectedVersion: number; name: string; unitPrice: number; currency: string }) => api.editProduct(id, body),
    onSuccess: invalidate,
    onError: onConflict,
  })
  const publish = useMutation({
    mutationFn: ({ expectedVersion }: { expectedVersion: number }) => api.publishProduct(id, expectedVersion),
    onSuccess: async () => { setConfirm(undefined); await invalidate() },
    onError: onConflict,
  })
  const unpublish = useMutation({
    mutationFn: ({ expectedVersion }: { expectedVersion: number }) => api.unpublishProduct(id, expectedVersion),
    onSuccess: async () => { setConfirm(undefined); await invalidate() },
    onError: onConflict,
  })
  const addSku = useMutation({
    mutationFn: (body: { expectedVersion: number; skuNo: string; unitPrice: number; currency: string; stockType: 'PHYSICAL' | 'SERVICE'; specification: Record<string, string> }) => api.addSku(id, body),
    onSuccess: invalidate,
    onError: onConflict,
  })
  const saveSku = useMutation({
    mutationFn: (payload: { skuId: string; expectedVersion: number; unitPrice: number; currency: string; stockType: 'PHYSICAL' | 'SERVICE'; specification: Record<string, string> }) =>
      api.editSku(id, payload.skuId, {
        expectedVersion: payload.expectedVersion,
        unitPrice: payload.unitPrice,
        currency: payload.currency,
        stockType: payload.stockType,
        specification: payload.specification,
      }),
    onSuccess: async () => { setEditing(undefined); await invalidate() },
    onError: onConflict,
  })

  if (product.isLoading) return <div className="workspace"><StateBanner tone="loading" title="正在读取商品" detail="正在获取商品目录及规格。" /></div>
  if (product.isError || !product.data) {
    const problem = describeProblem(product.error)
    return <div className="workspace"><StateBanner tone="error" title={problem.title} detail={problem.detail} /><Link to={list}>返回列表</Link></div>
  }
  const current = product.data
  const draft = current.status === productStatusSchema.enum.DRAFT
  const draftName = name || current.name
  const draftPrice = unitPrice || String(current.unitPrice)
  const draftCurrency = currency || current.currency

  function submitEdit(event: FormEvent) {
    event.preventDefault()
    setFormError('')
    try {
      edit.mutate({
        expectedVersion: current.version,
        name: draftName.trim(),
        unitPrice: parseInteger(draftPrice, '单价', { min: 0 }),
        currency: parseCurrencyCode(draftCurrency),
      })
    } catch (cause) {
      setFormError(cause instanceof Error ? cause.message : '输入无效')
    }
  }

  function submitSku(event: FormEvent) {
    event.preventDefault()
    setSkuAddError('')
    try {
      const specification: Record<string, string> = {}
      if (specKey.trim() && specValue.trim()) specification[specKey.trim()] = specValue.trim()
      addSku.mutate({
        expectedVersion: current.version,
        skuNo: skuNo.trim(),
        unitPrice: parseInteger(skuPrice, 'SKU 单价', { min: 0 }),
        currency: parseCurrencyCode(skuCurrency || current.currency),
        stockType,
        specification,
      })
    } catch (cause) {
      setSkuAddError(cause instanceof Error ? cause.message : '输入无效')
    }
  }

  function submitSkuEdit(event: FormEvent) {
    event.preventDefault()
    if (!editing) return
    setSkuEditError('')
    try {
      const specification = { ...editing.specification }
      if (editSpecKey.trim() && editSpecValue.trim()) specification[editSpecKey.trim()] = editSpecValue.trim()
      saveSku.mutate({
        skuId: editing.skuId,
        expectedVersion: current.version,
        unitPrice: parseInteger(editPrice, 'SKU 单价', { min: 0 }),
        currency: parseCurrencyCode(editCurrency),
        stockType: editStockType,
        specification,
      })
    } catch (cause) {
      setSkuEditError(cause instanceof Error ? cause.message : '输入无效')
    }
  }

  return (
    <div className="workspace">
      <PageHeader
        title={current.name}
        description={`版本 ${current.version} · 目录价用于商品管理，实际应付额以订单为准。`}
        actions={<>
          <Link className="button secondary" to={list}>返回列表</Link>
          {draft && <Button tone="primary" disabled={publish.isPending} aria-haspopup="dialog" onClick={() => setConfirm('publish')}>发布</Button>}
          {current.status === productStatusSchema.enum.PUBLISHED &&
            <Button disabled={unpublish.isPending} aria-haspopup="dialog" onClick={() => setConfirm('unpublish')}>下架</Button>}
        </>}
      />
      <MutationBanner
        error={edit.error ?? addSku.error}
        success={edit.data || publish.data || unpublish.data || addSku.data || saveSku.data ? `resourceId=${(edit.data ?? publish.data ?? unpublish.data ?? addSku.data ?? saveSku.data)?.resourceId}` : undefined}
      />
      <div className="section-gap">
        <Panel>
          <h2>商品摘要</h2>
          <dl className="summary-fields">
            <div><dt>发布状态</dt><dd><Badge tone={current.status === productStatusSchema.enum.PUBLISHED ? 'good' : 'neutral'}>{productStatusLabels[current.status]}</Badge></dd></div>
            <div><dt>目录价</dt><dd className="summary-amount">{formatMinor(current.unitPrice, current.currency)}</dd></div>
            <div><dt>主 SKU</dt><dd className="mono">{current.skuId}</dd></div>
            <div><dt>商品 ID</dt><dd className="mono">{current.productId}</dd></div>
            <div><dt>发布修订</dt><dd className="mono">{current.publishedRevision ?? '无'}</dd></div>
          </dl>
        </Panel>
        <Panel className="table-panel">
          <h2 className="table-title">商品 SKU</h2>
          {skus.isLoading ? <StateBanner tone="loading" title="正在读取 SKU" detail="正在获取当前商品的规格记录。" /> : null}
          {skus.isError ? <StateBanner tone="error" title={describeProblem(skus.error).title} detail={describeProblem(skus.error).detail} /> : null}
          {skus.data && skus.data.length > 0 ? (
            <>
              <DataTable label="商品 SKU 列表" className="trade-list-table pin-key">
                <thead><tr><th>SKU 标题</th><th>SKU 编码</th><th>SKU ID</th><th>规格</th><th>库存类型</th><th className="numeric-cell">单价</th><th>币种</th><th>修订</th>{draft ? <th>操作</th> : null}</tr></thead>
                <tbody>
                  {skus.data.map((row) => (
                    <tr key={row.skuId}>
                      <td>{row.title}</td>
                      <td className="mono">{row.skuNo}</td>
                      <td className="mono">{row.skuId}</td>
                      <td>{Object.entries(row.specification).map(([key, value]) => `${key} ${value}`).join('、') || '—'}</td>
                      <td>{stockTypeLabels[row.stockType]}</td>
                      <td className="numeric-cell">{formatMinorAmount(row.unitPrice)}</td>
                      <td>{row.currency}</td>
                      <td className="mono">{row.revision}</td>
                      {draft ? (
                        <td className="row-action-cell">
                          <Button tone="ghost" aria-haspopup="dialog" onClick={() => {
                            const entry = Object.entries(row.specification)[0]
                            setEditing(row)
                            setEditPrice(String(row.unitPrice))
                            setEditCurrency(row.currency)
                            setEditStockType(row.stockType)
                            setEditSpecKey(entry?.[0] ?? '')
                            setEditSpecValue(entry?.[1] ?? '')
                            setSkuEditError('')
                            saveSku.reset()
                          }}>编辑</Button>
                        </td>
                      ) : null}
                    </tr>
                  ))}
                </tbody>
              </DataTable>
              <CursorPager after={skuAfter} pageSize={20} lastId={skus.data.at(-1)?.skuId} length={skus.data.length} onReset={() => setSkuAfter(0)} onNext={setSkuAfter} />
            </>
          ) : skus.data ? <div className="panel-inset"><StateBanner tone="info" title="还没有 SKU" detail="草稿阶段可以追加 SKU。" /></div> : null}
        </Panel>
        {draft ? (
          <div className="page-grid">
            <Panel>
              <h2>编辑草稿</h2>
              <StackForm onSubmit={submitEdit} actions={<Button tone="primary" type="submit" disabled={edit.isPending}>保存</Button>}>
                <Field label="名称" wide><input value={name} placeholder={current.name} onChange={(event) => setName(event.target.value)} /></Field>
                <Field label="单价（最小单位）" error={formError}>
                  <input value={unitPrice} placeholder={String(current.unitPrice)} onChange={(event) => setUnitPrice(event.target.value)} />
                </Field>
                <Field label="币种"><input value={currency} placeholder={current.currency} onChange={(event) => setCurrency(event.target.value)} /></Field>
              </StackForm>
            </Panel>
            <Panel>
              <h2>追加 SKU</h2>
              <StackForm onSubmit={submitSku} actions={<Button tone="primary" type="submit" disabled={addSku.isPending}>追加</Button>}>
                <Field label="SKU 编码"><input value={skuNo} onChange={(event) => setSkuNo(event.target.value)} required /></Field>
                <Field label="单价（最小单位）" error={skuAddError}><input value={skuPrice} onChange={(event) => setSkuPrice(event.target.value)} required /></Field>
                <Field label="币种"><input value={skuCurrency} placeholder={current.currency} onChange={(event) => setSkuCurrency(event.target.value)} /></Field>
                <Field label="库存类型">
                  <select value={stockType} onChange={(event) => setStockType(event.target.value as 'PHYSICAL' | 'SERVICE')}>
                    <option value="PHYSICAL">实物</option>
                    <option value="SERVICE">服务</option>
                  </select>
                </Field>
                <Field label="规格键"><input value={specKey} onChange={(event) => setSpecKey(event.target.value)} /></Field>
                <Field label="规格值"><input value={specValue} onChange={(event) => setSpecValue(event.target.value)} /></Field>
              </StackForm>
            </Panel>
          </div>
        ) : <StateBanner tone="info" title="已发布快照不可改商品正文" detail="下架不会清理库存或历史快照。" />}
      </div>
      {draft && editing && (
        <Modal title={`编辑 SKU ${editing.skuNo}`} pending={saveSku.isPending} onClose={() => setEditing(undefined)}>
          <form className="modal-form-grid" onSubmit={submitSkuEdit}>
            <MutationBanner error={saveSku.error} />
            <Field label="单价" error={skuEditError}><input data-initial-focus value={editPrice} onChange={(event) => setEditPrice(event.target.value)} required /></Field>
            <Field label="币种"><input value={editCurrency} onChange={(event) => setEditCurrency(event.target.value)} /></Field>
            <Field label="库存类型">
              <select value={editStockType} onChange={(event) => setEditStockType(event.target.value as 'PHYSICAL' | 'SERVICE')}>
                <option value="PHYSICAL">实物</option>
                <option value="SERVICE">服务</option>
              </select>
            </Field>
            <Field label="规格键"><input value={editSpecKey} onChange={(event) => setEditSpecKey(event.target.value)} /></Field>
            <Field label="规格值"><input value={editSpecValue} onChange={(event) => setEditSpecValue(event.target.value)} /></Field>
            <footer className="modal-actions">
              <Button onClick={() => setEditing(undefined)} disabled={saveSku.isPending}>取消</Button>
              <Button tone="primary" type="submit" disabled={saveSku.isPending}>{saveSku.isPending ? '正在保存…' : '保存 SKU'}</Button>
            </footer>
          </form>
        </Modal>
      )}
      {confirm === 'publish' ? (
        <ConfirmDialog
          title="发布商品"
          description="发布后快照不可改商品正文。目录价仍不是可付金额。"
          confirmLabel="确认发布"
          tone="primary"
          pending={publish.isPending}
          onClose={() => setConfirm(undefined)}
          onConfirm={() => publish.mutate({ expectedVersion: current.version })}
        ><MutationBanner error={publish.error} /></ConfirmDialog>
      ) : null}
      {confirm === 'unpublish' ? (
        <ConfirmDialog
          title="下架商品"
          description="下架不会清理库存或历史快照。"
          confirmLabel="确认下架"
          pending={unpublish.isPending}
          onClose={() => setConfirm(undefined)}
          onConfirm={() => unpublish.mutate({ expectedVersion: current.version })}
        ><MutationBanner error={unpublish.error} /></ConfirmDialog>
      ) : null}
    </div>
  )
}
