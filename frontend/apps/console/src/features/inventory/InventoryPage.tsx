import { focusWorkSection } from '../../shared/ui/listScroll'
import { WorkSections } from '../../components/WorkspaceNavigation'
import { InventoryAlerts } from './InventoryAlerts'
import { NumberedPagePager, NumberedCursorPager } from '../../components/NumberedPager'
import { balanceSchema, warehouseSchema, ledgerEntrySchema } from '../../shared/api/schemas'
import { useLocalListPage, useNumberedList, pageItems } from '../../shared/ui/useNumberedList'
import { appliedFilters } from '../../shared/ui/appliedFilters'
import { formatInteger } from '../../shared/ui/display'
import { usePageSize } from '../../shared/ui/usePageSize'
import { Select } from '../../components/Select'
import { useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Badge, Button, ConfirmDialog, Field, PageHeader, Panel, StackForm, StateBanner, DataTable, CopyValue } from '../../components/ui'
import { api } from '../../shared/api/client'
import { MutationBanner, QueryGate } from '../../shared/ui/QueryGate'
import { parseInteger, parsePositiveId } from '../../shared/api/money'
import { parseMemberId } from '../../shared/api/validate'
import { FilterBar } from '../../shared/ui/FilterBar'
import { useAdminListSearch } from '../../shared/ui/useAdminListSearch'
import { formatBeijingTime } from '../../shared/ui/display'

export function InventoryPage() {
  const [whAfter, setWhAfter, warehousePage, setWarehousePage] = useLocalListPage<number>(0)
  const [warehouseSize, setWarehouseSize] = usePageSize('warehouses', 20, () => setWhAfter(0))
  const { params, after: balAfter, page: balPage, setPage: setBalPage, apply, remove, setAfter: setBalAfter, pageSize, setPageSize } = useAdminListSearch()
  const balanceFilters = {
    warehouseId: params.get('warehouseId') || undefined,
    skuId: params.get('skuId') || undefined,
    maxAvailable: params.get('maxAvailable') || undefined,
  }
  const [warehouseFilter, setWarehouseFilter] = useState(balanceFilters.warehouseId ?? '')
  const [skuFilter, setSkuFilter] = useState(balanceFilters.skuId ?? '')
  const [availableFilter, setAvailableFilter] = useState(balanceFilters.maxAvailable ?? '')
  const [balanceError, setBalanceError] = useState('')
  const [warehouseNo, setWarehouseNo] = useState('')
  const [warehouseName, setWarehouseName] = useState('')
  const [adjWarehouse, setAdjWarehouse] = useState('')
  const [adjSku, setAdjSku] = useState('')
  const [delta, setDelta] = useState('')
  const [reason, setReason] = useState('')
  const [ledgerWarehouse, setLedgerWarehouse] = useState('')
  const [ledgerSku, setLedgerSku] = useState('')
  const [ledgerAfter, setLedgerAfter, ledgerPage, setLedgerPage] = useLocalListPage<number>(0)
  const [ledgerSize, setLedgerSize] = usePageSize('inventory-ledger', 20, () => setLedgerAfter(0))
  const [ledgerSelection, setLedgerSelection] = useState<{ warehouseId: number; skuId: number }>()
  const [ledgerError, setLedgerError] = useState('')
  const [formError, setFormError] = useState('')
  const [confirmAdjust, setConfirmAdjust] = useState(false)
  const queryClient = useQueryClient()
  const balances = useNumberedList({ queryKey: ['inventories', balAfter, pageSize, balanceFilters], queryFn: () => api.inventories({ after: balAfter, limit: pageSize, ...balanceFilters }) }, { path: 'numbered-pages/inventories', schema: balanceSchema, after: balAfter, page: balPage, pageSize: pageSize, filters: balanceFilters }, pageItems)
  const warehouses = useNumberedList({ queryKey: ['warehouses', whAfter, warehouseSize], enabled: balances.isFetched, queryFn: () => api.warehouses(whAfter, warehouseSize) }, { path: 'numbered-pages/warehouses', schema: warehouseSchema, after: whAfter, page: warehousePage, pageSize: warehouseSize }, pageItems)
  const ledgers = useNumberedList({
    queryKey: ['ledgers', ledgerSelection, ledgerAfter, ledgerSize],
    queryFn: () => ledgerSelection
      ? api.inventoryLedgers(ledgerSelection.warehouseId, ledgerSelection.skuId, ledgerAfter, ledgerSize)
      : Promise.reject(new Error('请先选择仓库和 SKU')),
    enabled: Boolean(ledgerSelection),
  }, { path: 'numbered-pages/inventory-ledger', schema: ledgerEntrySchema, after: ledgerAfter, page: ledgerPage, pageSize: ledgerSize, filters: ledgerSelection }, pageItems)
  const createWh = useMutation({
    mutationFn: api.createWarehouse,
    onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: ['warehouses'] }) },
  })
  const adjust = useMutation({
    mutationFn: api.adjustInventory,
    onSuccess: async () => {
      setConfirmAdjust(false)
      await queryClient.invalidateQueries({ queryKey: ['inventories'] })
      await queryClient.invalidateQueries({ queryKey: ['inventory-alerts'] })
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
      delta: parseInteger(delta, '调整数量', { min: -1_000_000_000, max: 1_000_000_000 }),
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
    loadLedger(ledgerWarehouse, ledgerSku)
  }

  function loadLedger(warehouseId: string, skuId: string) {
    setLedgerError('')
    try {
      const selection = { warehouseId: parsePositiveId(warehouseId, '仓库 ID'), skuId: parsePositiveId(skuId, 'SKU ID') }
      if (ledgerAfter === 0 && ledgerSelection?.warehouseId === selection.warehouseId && ledgerSelection.skuId === selection.skuId) {
        void ledgers.refetch()
      }
      setLedgerSelection(selection)
      setLedgerAfter(0)
    } catch (cause) {
      setLedgerError(cause instanceof Error ? cause.message : '输入无效')
    }
  }

  function viewLedger(warehouseId: string, skuId: string) {
    setLedgerWarehouse(warehouseId)
    setLedgerSku(skuId)
    loadLedger(warehouseId, skuId)
    focusWorkSection('inventory-ledger')
  }

  function prepareAdjustment(warehouseId: string, skuId: string) {
    setAdjWarehouse(warehouseId)
    setAdjSku(skuId)
    focusWorkSection('inventory-adjustment')
  }

  function clearFilters() {
    setWarehouseFilter('')
    setSkuFilter('')
    setAvailableFilter('')
    setBalanceError('')
    apply({})
  }

  return (
    <div className="workspace queue-workspace">
      <PageHeader
        title="仓库与库存"
        description="先核对库存余额和预占，再按仓库与 SKU 查看流水或登记盘点调整。"
      />
      <WorkSections sections={[{ id: 'inventory-balances', label: '库存余额' }, { id: 'inventory-warehouses', label: '仓库' }, { id: 'inventory-ledger', label: '库存流水' }, { id: 'inventory-adjustment', label: '盘点调整' }]} />
      <MutationBanner error={createWh.error ?? adjust.error} success={createWh.data || adjust.data ? `resourceId=${(createWh.data ?? adjust.data)?.resourceId}` : undefined} />
      <FilterBar applied={appliedFilters(params, remove, [
          ['仓库 ID', 'warehouseId', () => setWarehouseFilter('')],
          ['SKU ID', 'skuId', () => setSkuFilter('')],
          ['可售数量上限', 'maxAvailable', () => setAvailableFilter('')],
        ])}
          onReset={clearFilters} onSubmit={() => {
        setBalanceError('')
        try {
          const available = availableFilter.trim()
          apply({
            warehouseId: warehouseFilter.trim() ? parseMemberId(warehouseFilter, '仓库 ID') : undefined,
            skuId: skuFilter.trim() ? parseMemberId(skuFilter, 'SKU ID') : undefined,
            maxAvailable: available ? String(parseInteger(available, '可售数量上限', { min: 0 })) : undefined,
          })
        } catch (cause) {
          setBalanceError(cause instanceof Error ? cause.message : '筛选无效')
        }
      }}>
        <Field label="按仓库 ID"><input value={warehouseFilter} onChange={(event) => setWarehouseFilter(event.target.value)} inputMode="numeric" /></Field>
        <Field label="按 SKU ID"><input value={skuFilter} onChange={(event) => setSkuFilter(event.target.value)} inputMode="numeric" /></Field>
        <Field label="可售数量上限"><input value={availableFilter} onChange={(event) => setAvailableFilter(event.target.value)} inputMode="numeric" /></Field>
      </FilterBar>
      {balanceError ? <StateBanner tone="warn" title="筛选条件无效" detail={balanceError} /> : null}
      <div className="section-gap">
        <Panel className="table-panel" id="inventory-balances" tabIndex={-1}>
          <h2 className="table-title">库存余额</h2>
          <QueryGate query={balances} emptyTitle="没有余额行" emptyDetail="当前条件没有匹配的库存余额。">
            {(rows) => (
              <>
                <DataTable label="库存余额列表" className="pin-actions">
                  <thead><tr><th>仓库 ID</th><th>SKU ID</th><th className="numeric-cell">可售</th><th className="numeric-cell">预占</th><th className="numeric-cell">已售</th><th className="numeric-cell">总量</th><th>操作</th></tr></thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr key={row.id}>
                        <td><CopyValue value={row.warehouseId} name="仓库 ID" /></td>
                        <td className="mono">{row.skuId}</td>
                        <td className="numeric-cell">{row.available === 0 ? <Badge tone="warn">0 · 缺货</Badge> : formatInteger(row.available)}</td>
                        <td className="numeric-cell">{formatInteger(row.reserved)}</td>
                        <td className="numeric-cell">{formatInteger(row.sold)}</td>
                        <td className="numeric-cell">{formatInteger(row.stockTotal)}</td>
                        <td className="row-action-cell">
                          <Button tone="ghost" onClick={() => viewLedger(row.warehouseId, row.skuId)}>查看流水</Button>
                          <Button tone="ghost" onClick={() => prepareAdjustment(row.warehouseId, row.skuId)}>盘点调整</Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </DataTable>
              </>
            )}
          </QueryGate>
          <NumberedPagePager numbered={balances.numbered} onNumber={setBalPage} scope={['inventory-balances', balanceFilters]} after={balAfter} onPage={setBalAfter} pageSize={pageSize} onPageSize={setPageSize}
            result={balances.isSuccess ? { items: balances.data, nextAfter: balances.data.at(-1)?.id ?? null, hasMore: balances.data.length === pageSize } : undefined} />
        </Panel>
        <InventoryAlerts key={`${balanceFilters.warehouseId ?? ''}:${balanceFilters.skuId ?? ''}`}
        filters={{ warehouseId: balanceFilters.warehouseId, skuId: balanceFilters.skuId }} />
      <Panel className="table-panel" id="inventory-warehouses" tabIndex={-1}>
          <h2 className="table-title">仓库</h2>
          {balances.isFetched ? <QueryGate query={warehouses} emptyTitle="没有仓库" emptyDetail="先创建仓库，再做盘点。">
            {(rows) => (
              <>
                <DataTable label="仓库列表">
                  <thead><tr><th>仓库名称</th><th>仓库编号</th><th>仓库 ID</th><th>版本</th></tr></thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr key={row.id}><td>{row.name}</td><td className="mono">{row.warehouseNo}</td><td className="mono">{row.id}</td><td className="mono">{row.version}</td></tr>
                    ))}
                  </tbody>
                </DataTable>
              </>
            )}
          </QueryGate> : <StateBanner tone="loading" title="正在读取仓库" detail="先读取当前库存余额，仓库清单随后显示。" />}
          <NumberedCursorPager numbered={warehouses.numbered} onNumber={setWarehousePage} scope={['warehouses']} after={whAfter} onPage={setWhAfter} pageSize={warehouseSize} onPageSize={setWarehouseSize}
            rows={warehouses.isSuccess ? warehouses.data : undefined} getCursor={(row) => row.id} />
        </Panel>
        <div className="registry-layout">
          <Panel>
            <h2>创建仓库</h2>
            <StackForm onSubmit={submitWarehouse} actions={<Button tone="primary" type="submit" loading={createWh.isPending}>创建</Button>}>
              <Field label="仓库编号"><input value={warehouseNo} onChange={(event) => setWarehouseNo(event.target.value)} required /></Field>
              <Field label="仓库名称"><input value={warehouseName} onChange={(event) => setWarehouseName(event.target.value)} required /></Field>
            </StackForm>
          </Panel>
          <section id="inventory-adjustment" tabIndex={-1}>
            <Panel>
              <h2>盘点调整</h2>
              <StackForm onSubmit={submitAdjust} actions={<Button tone="primary" type="submit" aria-haspopup="dialog" loading={adjust.isPending}>提交</Button>}>
                <Field label="仓库">
                  <Select value={adjWarehouse} onChange={(value) => setAdjWarehouse(value)} required>
                    <option value="">选择仓库</option>
                    {adjWarehouse && !warehouses.data?.some((row) => row.id === adjWarehouse) ? <option value={adjWarehouse}>仓库 ID {adjWarehouse}</option> : null}
                    {(warehouses.data ?? []).map((row) => (
                      <option key={row.id} value={row.id}>{row.name} · {row.warehouseNo}</option>
                    ))}
                  </Select>
                </Field>
                <Field label="SKU ID" hint="可从余额行带入，或填写尚未建余额的 SKU。">
                  <input value={adjSku} onChange={(event) => setAdjSku(event.target.value)} required />
                </Field>
                <Field label="调整数量（整数，可负）" error={formError}><input value={delta} onChange={(event) => setDelta(event.target.value)} required /></Field>
                <Field label="原因"><input value={reason} onChange={(event) => setReason(event.target.value)} maxLength={255} required /></Field>
              </StackForm>
            </Panel>
          </section>
        </div>
        <section id="inventory-ledger" tabIndex={-1}>
          <Panel>
            <h2>库存流水</h2>
            <StackForm onSubmit={applyLedger} actions={<Button tone="primary" type="submit">查询</Button>}>
              <Field label="仓库" error={ledgerError}>
                <Select value={ledgerWarehouse} onChange={(value) => setLedgerWarehouse(value)}>
                  <option value="">选择仓库</option>
                  {ledgerWarehouse && !warehouses.data?.some((row) => row.id === ledgerWarehouse) ? <option value={ledgerWarehouse}>仓库 ID {ledgerWarehouse}</option> : null}
                  {(warehouses.data ?? []).map((row) => (
                    <option key={row.id} value={row.id}>{row.name} · {row.warehouseNo}</option>
                  ))}
                </Select>
              </Field>
              <Field label="SKU ID"><input value={ledgerSku} onChange={(event) => setLedgerSku(event.target.value)} /></Field>
            </StackForm>
            {ledgerSelection ? (
              <p>已查询仓库 {ledgerSelection.warehouseId}，SKU {ledgerSelection.skuId}</p>
            ) : null}
            {ledgerSelection ? (
              <>
                <QueryGate query={ledgers} emptyTitle="没有流水" emptyDetail="该仓库与 SKU 组合尚无审计记录。">
                  {(rows) => (
                    <>
                      <DataTable label="库存流水列表" className="trade-list-table">
                        <thead><tr><th className="time-cell">发生时间（北京时间）</th><th>操作类型</th><th>可售变化</th><th>预占变化</th><th>已售变化</th><th>总量变化</th><th>业务引用</th><th>原因</th></tr></thead>
                        <tbody>
                          {rows.map((row) => (
                            <tr key={row.id}>
                              <td className="time-cell">{formatBeijingTime(row.occurredAt)}</td>
                              <td>{row.operationType}</td>
                              <td>{row.availableDelta}</td>
                              <td>{row.reservedDelta}</td>
                              <td>{row.soldDelta}</td>
                              <td>{row.totalDelta}</td>
                              <td className="mono">{row.operationRef}</td>
                              <td><span className="table-long-text" title={row.reason}>{row.reason}</span></td>
                            </tr>
                          ))}
                        </tbody>
                      </DataTable>
                    </>
                  )}
                </QueryGate>
                <NumberedCursorPager numbered={ledgers.numbered} onNumber={setLedgerPage} scope={['inventory-ledger', ledgerSelection]} after={ledgerAfter} onPage={setLedgerAfter} pageSize={ledgerSize} onPageSize={setLedgerSize}
                  rows={ledgers.isSuccess ? ledgers.data : undefined} getCursor={(row) => row.id} />
              </>
            ) : null}
          </Panel>
        </section>
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
          <MutationBanner error={adjust.error} />
          <p>仓库 {adjWarehouse}，SKU {adjSku}，调整数量 {delta}</p>
        </ConfirmDialog>
      ) : null}
    </div>
  )
}
