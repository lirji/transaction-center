import { useRef, useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ImageOff, Upload } from 'lucide-react'
import { z } from 'zod'
import { api } from '../../shared/api/client'
import { catalogDraftImportSchema, type CatalogDraftImport } from '../../shared/api/schemas'
import { Button, ConfirmDialog, Field, Modal } from '../../components/ui'
import { MutationBanner } from '../../shared/ui/QueryGate'

export const PRODUCT_BATCH_LIMIT = 100
const MAX_IMPORT_BYTES = 256 * 1024
const draftFileSchema = z.array(catalogDraftImportSchema).min(1).max(PRODUCT_BATCH_LIMIT)

export type ProductSelection = { productId: string; version: number }
type ProductAction = 'PUBLISH' | 'OFFLINE'

export function ProductOperations({ selected, clear, resetPage }: {
  selected: ProductSelection[]
  clear: () => void
  resetPage: () => void
}) {
  const [open, setOpen] = useState(false)
  const [fileRows, setFileRows] = useState<CatalogDraftImport[]>([])
  const [fileError, setFileError] = useState('')
  const currentFile = useRef<File>(undefined)
  const [action, setAction] = useState<ProductAction>()
  const [message, setMessage] = useState('')
  const client = useQueryClient()
  const upload = useMutation({
    mutationFn: () => api.importProductDrafts(fileRows),
    onSuccess: async (result) => {
      setOpen(false)
      setMessage(`已导入 ${result.items.length} 条商品草稿`)
      resetPage()
      clear()
      await client.invalidateQueries({ queryKey: ['products'] })
    },
  })
  const batch = useMutation({
    mutationFn: (next: ProductAction) => api.changeProductBatch({ action: next, items: selected }),
    onSuccess: async (result) => {
      setAction(undefined)
      setMessage(`已处理 ${result.items.length} 条商品`)
      clear()
      await client.invalidateQueries({ queryKey: ['products'] })
      await client.invalidateQueries({ queryKey: ['product'] })
    },
  })

  async function readFile(file?: File) {
    currentFile.current = file
    setFileRows([])
    setFileError('')
    if (!file) return
    if (file.size > MAX_IMPORT_BYTES) {
      setFileError('请使用不超过 256 KB 的清单文件')
      return
    }
    try {
      const parsed = draftFileSchema.safeParse(JSON.parse((await file.text()).replace(/^\uFEFF/, '')))
      if (!parsed.success) {
        const row = parsed.error.issues[0]?.path[0]
        throw new Error(typeof row === 'number' ? `第 ${row + 1} 行格式无效` : '请提供 1 至 100 行商品草稿')
      }
      if (currentFile.current === file) setFileRows(parsed.data)
    } catch (error) {
      if (currentFile.current === file) setFileError(error instanceof SyntaxError ? '清单须为有效的 JSON 数组' : error instanceof Error ? error.message : '文件无法读取')
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (fileRows.length) upload.mutate()
  }

  return <>
    <Button aria-haspopup="dialog" onClick={() => {
      currentFile.current = undefined
      setFileRows([])
      setFileError('')
      upload.reset()
      setOpen(true)
    }}><Upload size={16} aria-hidden="true" />导入草稿</Button>
    {selected.length > 0 && <div className="queue-actions" role="group" aria-label="批量商品操作">
      <span role="status">已选 {selected.length} 条</span>
      <Button onClick={() => { batch.reset(); setAction('PUBLISH') }}>批量上架</Button>
      <Button onClick={() => { batch.reset(); setAction('OFFLINE') }}>批量下架</Button>
      <Button tone="ghost" onClick={clear}>清空选择</Button>
    </div>}
    {message && <p className="panel-note" role="status">{message}</p>}
    {open && <Modal title="导入商品草稿" description="上传 JSON 清单。每条包含 productNo、name、skuNo、unitPrice（最小单位整数）和 currency。"
      pending={upload.isPending} onClose={() => setOpen(false)}>
      <form className="modal-form-grid" onSubmit={submit}>
        <MutationBanner error={upload.error} />
        <Field label="商品清单" error={fileError}>
          <input type="file" accept=".json,application/json" disabled={upload.isPending}
            onChange={(event) => void readFile(event.target.files?.[0])} />
        </Field>
        {fileRows.length > 0 && <p role="status">已校验 {fileRows.length} 条草稿</p>}
        <footer className="modal-actions">
          <Button onClick={() => setOpen(false)} disabled={upload.isPending}>取消</Button>
          <Button tone="primary" type="submit" loading={upload.isPending} disabled={fileRows.length === 0}>确认导入</Button>
        </footer>
      </form>
    </Modal>}
    {action && <ConfirmDialog title={action === 'PUBLISH' ? '批量上架' : '批量下架'} description={`已选择 ${selected.length} 条商品。各项版本及状态均匹配后整批提交。`}
      tone={action === 'PUBLISH' ? 'primary' : 'danger'} confirmLabel="确认批量操作" pending={batch.isPending}
      onClose={() => setAction(undefined)} onConfirm={() => batch.mutate(action)}>
      <MutationBanner error={batch.error} />
    </ConfirmDialog>}
  </>
}

export function ProductThumbnail({ url }: { url?: string | null }) {
  const [failed, setFailed] = useState<string>()
  return <span className="product-thumbnail">
    {url && failed !== url
      ? <img src={url} alt="" loading="lazy" referrerPolicy="no-referrer" onError={() => setFailed(url)} />
      : <ImageOff size={16} aria-label="暂无商品图" />}
  </span>
}

export function ProductImageEditor({ product, close }: {
  product: { productId: string; version: number; imageUrl?: string | null }
  close: () => void
}) {
  const [imageUrl, setImageUrl] = useState(product.imageUrl ?? '')
  const [error, setError] = useState('')
  const client = useQueryClient()
  const save = useMutation({
    mutationFn: (value: string | null) => api.saveProductImage(product.productId, { version: product.version, imageUrl: value }),
    onSuccess: async () => {
      close()
      await client.invalidateQueries({ queryKey: ['products'] })
      await client.invalidateQueries({ queryKey: ['product', product.productId] })
    },
  })
  function submit(event: FormEvent) {
    event.preventDefault()
    const value = imageUrl.trim()
    if (value && !/^https:\/\//i.test(value)) {
      setError('图片地址须以 https:// 开头')
      return
    }
    setError('')
    save.mutate(value || null)
  }
  return <Modal title="编辑商品展示图" description="使用可访问的 HTTPS 图片地址。地址留空将清除当前图片。"
    onClose={close} pending={save.isPending}>
    <form className="modal-form-grid" onSubmit={submit}>
      <MutationBanner error={save.error} />
      <Field label="HTTPS 图片地址" error={error}>
        <input value={imageUrl} onChange={(event) => setImageUrl(event.target.value)} maxLength={1024} />
      </Field>
      {product.imageUrl && <div className="queue-actions"><ProductThumbnail url={product.imageUrl} /><span>当前展示图</span></div>}
      <footer className="modal-actions">
        <Button onClick={close} disabled={save.isPending}>取消</Button>
        <Button tone="primary" type="submit" loading={save.isPending}>保存展示图</Button>
      </footer>
    </form>
  </Modal>
}
