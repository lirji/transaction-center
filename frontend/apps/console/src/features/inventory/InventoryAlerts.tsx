import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Badge, Button, DataTable, Field, Modal, Panel } from '../../components/ui'
import { NumberedPager } from '../../components/NumberedPager'
import { api } from '../../shared/api/client'
import { formatInteger } from '../../shared/ui/display'
import { parseInteger } from '../../shared/api/money'
import type { InventoryAlert, InventoryAlertState } from '../../shared/api/schemas'
import { MutationBanner, QueryGate } from '../../shared/ui/QueryGate'
import { usePageSize } from '../../shared/ui/usePageSize'

const inventoryLabels: Record<InventoryAlertState, string> = {
  OUT_OF_STOCK: '缺货',
  LOW: '达到补货阈值',
  UNCONFIGURED: '未配置阈值',
  NORMAL: '正常',
}

const inventoryTones = { OUT_OF_STOCK: 'warn', LOW: 'warn', UNCONFIGURED: 'neutral', NORMAL: 'good' } as const

export function InventoryAlerts({ filters }: { filters: { warehouseId?: string; skuId?: string } }) {
  const [shown, setShown] = useState(false)
  const [state, setState] = useState<InventoryAlertState>()
  const [page, setPage] = useState(1)
  const [size, setSize] = usePageSize('inventory-alerts', 20, () => setPage(1))
  const [editing, setEditing] = useState<InventoryAlert>()
  const query = useQuery({
    queryKey: ['inventory-alerts', filters, state, page, size],
    queryFn: ({ signal }) => api.inventoryAlerts(page, size, { ...filters, state }, signal),
    enabled: shown,
    retry: false,
  })

  function choose(next?: InventoryAlertState) {
    setState(next)
    setPage(1)
  }

  return <Panel className="period-summary">
    <div className="panel-head">
      <h2>库存预警</h2>
      <Button aria-expanded={shown} onClick={() => setShown(!shown)}>{shown ? '收起预警' : '查看预警与补货阈值'}</Button>
    </div>
    {shown && <QueryGate query={query} emptyTitle="没有库存预警记录" emptyDetail="请检查仓库与 SKU 范围。">
      {(result) => <>
        <div className="queue-actions" role="group" aria-label="预警状态">
          <Button aria-pressed={!state} onClick={() => choose()}>全部</Button>
          {Object.entries(inventoryLabels).map(([value, label]) => {
            const item = result.totals.find((total) => total.state === value)
            return <Button key={value} aria-pressed={state === value} onClick={() => choose(item?.state ?? value as InventoryAlertState)}>
              {label} {formatInteger(item?.count ?? 0)}
            </Button>
          })}
        </div>
        <DataTable label="库存预警列表">
          <thead><tr><th>仓库 ID</th><th>SKU ID</th><th className="numeric-cell">可用量</th>
            <th className="numeric-cell">补货阈值</th><th>库存状态</th><th>操作</th></tr></thead>
          <tbody>{result.rows.items.map((row) => <tr key={row.warehouseId + ':' + row.skuId}>
            <td>{row.warehouseId}</td><td>{row.skuId}</td>
            <td className="numeric-cell">{formatInteger(row.available)}</td>
            <td className="numeric-cell">{row.reorderThreshold === null ? '未配置' : formatInteger(row.reorderThreshold)}</td>
            <td><Badge tone={inventoryTones[row.state]}>{inventoryLabels[row.state]}</Badge></td>
            <td><Button tone="ghost" aria-haspopup="dialog" onClick={() => setEditing(row)}>设置阈值</Button></td>
          </tr>)}</tbody>
        </DataTable>
        {result.rows.items.length === 0 && <p className="panel-note">当前筛选下没有库存项。</p>}
        <NumberedPager result={result.rows} onPage={setPage} onPageSize={setSize} />
      </>}
    </QueryGate>}
    {editing && <ReorderDialog row={editing} close={() => setEditing(undefined)} />}
  </Panel>
}

function ReorderDialog({ row, close }: { row: InventoryAlert; close: () => void }) {
  const [raw, setRaw] = useState(row.reorderThreshold?.toString() ?? '')
  const [error, setError] = useState('')
  const client = useQueryClient()
  const save = useMutation({
    mutationFn: (threshold: number | null) => api.saveInventoryRule(row.warehouseId, row.skuId, {
      revision: row.revision, reorderThreshold: threshold,
    }),
    onSuccess: async () => {
      close()
      await client.invalidateQueries({ queryKey: ['inventory-alerts'] })
    },
  })
  function submit(event: FormEvent) {
    event.preventDefault()
    try {
      const value = raw.trim() ? parseInteger(raw, '补货阈值', { min: 0 }) : null
      setError('')
      save.mutate(value)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '请检查阈值')
    }
  }

  return <Modal title="设置补货阈值" description={`仓库 ${row.warehouseId} · SKU ${row.skuId}。留空表示清除预警配置。`}
    pending={save.isPending} onClose={close}>
    <form className="modal-form-grid" onSubmit={submit}>
      <MutationBanner error={save.error} />
      <Field label="补货阈值" error={error}><input data-initial-focus value={raw} inputMode="numeric"
        onChange={(event) => setRaw(event.target.value)} /></Field>
      <footer className="modal-actions">
        <Button onClick={close} disabled={save.isPending}>取消</Button>
        <Button type="submit" tone="primary" loading={save.isPending}>保存阈值</Button>
      </footer>
    </form>
  </Modal>
}
