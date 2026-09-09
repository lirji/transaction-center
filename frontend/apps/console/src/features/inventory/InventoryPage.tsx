import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button, ConfirmDialog, CursorPager, Field, PageHeader, Panel, StackForm } from '../../components/ui'
import { api } from '../../shared/api/client'
import { MutationBanner, QueryGate } from '../../shared/ui/QueryGate'
import { parseInteger, parsePositiveId } from '../../shared/api/money'

export function InventoryPage() {
  const [whAfter, setWhAfter] = useState(0)
  const [balAfter, setBalAfter] = useState(0)
  const [warehouseNo, setWarehouseNo] = useState('')
  const [warehouseName, setWarehouseName] = useState('')
  const [adjWarehouse, setAdjWarehouse] = useState('')
  const [adjSku, setAdjSku] = useState('')
  const [delta, setDelta] = useState('')
  const [reason, setReason] = useState('')
  const [ledgerWarehouse, setLedgerWarehouse] = useState('')
  const [ledgerSku, setLedgerSku] = useState('')
  const [ledgerAfter, setLedgerAfter] = useState(0)
  const [ledgerError, setLedgerError] = useState('')
  const [formError, setFormError] = useState('')
  const [confirmAdjust, setConfirmAdjust] = useState(false)
  const queryClient = useQueryClient()
  const warehouses = useQuery({ queryKey: ['warehouses', whAfter], queryFn: () => api.warehouses(whAfter) })
  const balances = useQuery({ queryKey: ['inventories', balAfter], queryFn: () => api.inventories(balAfter) })
  const ledgersEnabled = Boolean(ledgerWarehouse && ledgerSku && !ledgerError)
  const ledgers = useQuery({
    queryKey: ['ledgers', ledgerWarehouse, ledgerSku, ledgerAfter],
    queryFn: () => api.inventoryLedgers(Number(ledgerWarehouse), Number(ledgerSku), ledgerAfter),
    enabled: ledgersEnabled,
  })
  const createWh = useMutation({
    mutationFn: api.createWarehouse,
    onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: ['warehouses'] }) },
  })
  const adjust = useMutation({
    mutationFn: api.adjustInventory,
    onSuccess: async () => {
      setConfirmAdjust(false)
      await queryClient.invalidateQueries({ queryKey: ['inventories'] })
      await queryClient.invalidateQueries({ queryKey: ['ledgers'] })
    },
  })

  function submitWarehouse(event: FormEvent) {
    event.preventDefault()
    createWh.mutate({ warehouseNo: warehouseNo.trim(), name: warehouseName.trim() })
  }

  function parsedAdjust() {
    return {
      warehouseId: parsePositiveId(adjWarehouse, '仓库 ID'),
      skuId: parsePositiveId(adjSku, 'SKU ID'),
      delta: parseInteger(delta, 'delta', { min: -1_000_000_000, max: 1_000_000_000 }),
      reason: reason.trim(),
    }
  }

  function submitAdjust(event: FormEvent) {
    event.preventDefault()
    setFormError('')
    try {
      parsedAdjust()
      setConfirmAdjust(true)
    } catch (cause) {
      setFormError(cause instanceof Error ? cause.message : '输入无效')
    }
  }

  function applyLedger(event: FormEvent) {
    event.preventDefault()
    setLedgerError('')
    try {
      setLedgerAfter(0)
      parsePositiveId(ledgerWarehouse, '仓库 ID')
      parsePositiveId(ledgerSku, 'SKU ID')
    } catch (cause) {
      setLedgerError(cause instanceof Error ? cause.message : '输入无效')
    }
  }

  function fillFromBalance(warehouseId: string, skuId: string) {
    setAdjWarehouse(warehouseId)
    setAdjSku(skuId)
    setLedgerWarehouse(warehouseId)
    setLedgerSku(skuId)
    setLedgerAfter(0)
    setLedgerError('')
  }

  return (
    <div className="workspace">
      <PageHeader
        eyebrow="Inventory"
        title="仓库与库存"
        description="盘点必须带原因并写入流水。本台不提供预占或释放库存。流水查询必须同时指定 warehouseId 与 skuId，不会发全库导出。"
      />
      <MutationBanner error={createWh.error ?? adjust.error} success={createWh.data || adjust.data ? `resourceId=${(createWh.data ?? adjust.data)?.resourceId}` : undefined} />
      <div className="section-gap">
        <Panel className="table-panel">
          <h2 style={{ padding: '16px 18px 0' }}>仓库</h2>
          <QueryGate query={warehouses} emptyTitle="没有仓库" emptyDetail="先创建仓库，再做盘点。">
            {(rows) => (
              <>
                <table className="data-table">
                  <thead><tr><th>仓库</th><th>编号</th><th>版本</th></tr></thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr key={row.id}><td>{row.name}</td><td className="mono">{row.warehouseNo} / {row.id}</td><td className="mono">{row.version}</td></tr>
                    ))}
                  </tbody>
                </table>
                <CursorPager after={whAfter} pageSize={20} lastId={rows.at(-1)?.id} length={rows.length} onReset={() => setWhAfter(0)} onNext={setWhAfter} />
              </>
            )}
          </QueryGate>
        </Panel>
        <div className="page-grid">
          <Panel>
            <h2>创建仓库</h2>
            <StackForm onSubmit={submitWarehouse} actions={<Button tone="primary" type="submit" disabled={createWh.isPending}>创建</Button>}>
              <Field label="warehouseNo"><input value={warehouseNo} onChange={(event) => setWarehouseNo(event.target.value)} required /></Field>
              <Field label="名称"><input value={warehouseName} onChange={(event) => setWarehouseName(event.target.value)} required /></Field>
            </StackForm>
          </Panel>
          <Panel>
            <h2>盘点调整</h2>
            <StackForm onSubmit={submitAdjust} actions={<Button tone="primary" type="submit" disabled={adjust.isPending}>提交</Button>}>
              <Field label="仓库">
                <select value={adjWarehouse} onChange={(event) => setAdjWarehouse(event.target.value)} required>
                  <option value="">选择仓库</option>
                  {(warehouses.data ?? []).map((row) => (
                    <option key={row.id} value={row.id}>{row.name} · {row.warehouseNo}</option>
                  ))}
                </select>
              </Field>
              <Field label="skuId" hint="可从余额行点「填入」，或填写尚未建余额的 SKU。">
                <input value={adjSku} onChange={(event) => setAdjSku(event.target.value)} required />
              </Field>
              <Field label="delta（整数，可负）" error={formError}><input value={delta} onChange={(event) => setDelta(event.target.value)} required /></Field>
              <Field label="原因"><input value={reason} onChange={(event) => setReason(event.target.value)} maxLength={255} required /></Field>
            </StackForm>
          </Panel>
        </div>
        <Panel className="table-panel">
          <h2 style={{ padding: '16px 18px 0' }}>余额</h2>
          <QueryGate query={balances} emptyTitle="没有余额行" emptyDetail="余额在盘点或出入库后才会出现。">
            {(rows) => (
              <>
                <table className="data-table">
                  <thead><tr><th>仓库 / SKU</th><th>可售</th><th>预占</th><th>已售</th><th>总量</th><th /></tr></thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr key={row.id}>
                        <td className="mono">{row.warehouseId} / {row.skuId}</td>
                        <td>{row.available}</td>
                        <td>{row.reserved}</td>
                        <td>{row.sold}</td>
                        <td>{row.stockTotal}</td>
                        <td><Button tone="ghost" onClick={() => fillFromBalance(row.warehouseId, row.skuId)}>填入</Button></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <CursorPager after={balAfter} pageSize={20} lastId={rows.at(-1)?.id} length={rows.length} onReset={() => setBalAfter(0)} onNext={setBalAfter} />
              </>
            )}
          </QueryGate>
        </Panel>
        <Panel>
          <h2>库存流水</h2>
          <StackForm onSubmit={applyLedger} actions={<Button tone="primary" type="submit">查询</Button>}>
            <Field label="仓库" error={ledgerError}>
              <select value={ledgerWarehouse} onChange={(event) => setLedgerWarehouse(event.target.value)}>
                <option value="">选择仓库</option>
                {(warehouses.data ?? []).map((row) => (
                  <option key={row.id} value={row.id}>{row.name} · {row.warehouseNo}</option>
                ))}
              </select>
            </Field>
            <Field label="skuId"><input value={ledgerSku} onChange={(event) => setLedgerSku(event.target.value)} /></Field>
          </StackForm>
          {ledgers.isFetched ? (
            <QueryGate query={ledgers} emptyTitle="没有流水" emptyDetail="该仓库与 SKU 组合尚无审计记录。">
              {(rows) => (
                <>
                  <table className="data-table">
                    <thead><tr><th>时间</th><th>类型</th><th>Δ可售</th><th>原因</th></tr></thead>
                    <tbody>
                      {rows.map((row) => (
                        <tr key={row.id}>
                          <td className="mono">{row.occurredAt}</td>
                          <td>{row.operationType}</td>
                          <td>{row.availableDelta}</td>
                          <td>{row.reason}<small className="mono">{row.operationRef}</small></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  <CursorPager after={ledgerAfter} pageSize={20} lastId={rows.at(-1)?.id} length={rows.length} onReset={() => setLedgerAfter(0)} onNext={setLedgerAfter} />
                </>
              )}
            </QueryGate>
          ) : null}
        </Panel>
      </div>
      {confirmAdjust ? (
        <ConfirmDialog
          title="确认盘点调整"
          description="盘点会写入库存流水。本台不会预占或释放库存。"
          confirmLabel="确认提交"
          tone="primary"
          pending={adjust.isPending}
          onClose={() => setConfirmAdjust(false)}
          onConfirm={() => {
            try {
              adjust.mutate(parsedAdjust())
            } catch (cause) {
              setFormError(cause instanceof Error ? cause.message : '输入无效')
              setConfirmAdjust(false)
            }
          }}
        >
          <p>仓库 {adjWarehouse} · SKU {adjSku} · delta {delta}</p>
        </ConfirmDialog>
      ) : null}
    </div>
  )
}
