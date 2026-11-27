import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Badge, Button, ConfirmDialog, Field, PageHeader, Panel, StackForm, StateBanner, CopyValue } from '../../components/ui'
import { api, ApiProblem } from '../../shared/api/client'
import { MutationBanner } from '../../shared/ui/QueryGate'
import { invalidateIfVersionConflict } from '../../shared/ui/invalidateIfVersionConflict'

export function MerchantPage() {
  const [merchantNo, setMerchantNo] = useState('')
  const [name, setName] = useState('')
  const [reason, setReason] = useState('')
  const [confirmOpen, setConfirmOpen] = useState(false)
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: ['merchant'], queryFn: api.merchant, retry: false })
  const missing = query.error instanceof ApiProblem && query.error.status === 404
  const create = useMutation({
    mutationFn: api.createMerchant,
    onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: ['merchant'] }) },
  })
  const change = useMutation({
    mutationFn: api.changeMerchantStatus,
    onSuccess: async () => {
      setConfirmOpen(false)
      setReason('')
      await queryClient.invalidateQueries({ queryKey: ['merchant'] })
    },
    onError: (error) => void invalidateIfVersionConflict(queryClient, error, ['merchant']),
  })

  function submitCreate(event: FormEvent) {
    event.preventDefault()
    create.mutate({ merchantNo: merchantNo.trim(), name: name.trim() })
  }

  const nextStatus = query.data?.status === 'FROZEN' ? 'ACTIVE' : 'FROZEN'

  return (
    <div className="workspace">
      <PageHeader
        title="逻辑商户"
        description="管理当前租户的商户资料和接单状态，按需冻结或恢复新订单受理。"
      />
      <MutationBanner error={create.error ?? change.error} success={create.data ? `已创建 resourceId=${create.data.resourceId}` : change.data ? `已变更 resourceId=${change.data.resourceId}` : undefined} />
      {query.isLoading ? <StateBanner tone="loading" title="正在读取" detail="GET /admin/merchant" /> : null}
      {query.isError && !missing ? <StateBanner tone="error" title="无法读取商户" detail={query.error instanceof Error ? query.error.message : '读取失败'} /> : null}
      {query.data ? (
        <Panel>
          <h2>商户信息</h2>
          <dl className="kv">
            <dt>商户 ID</dt><dd><CopyValue value={query.data.merchantId} name="商户 ID" /></dd>
            <dt>商户号</dt><dd><CopyValue value={query.data.merchantNo} name="商户号" /></dd>
            <dt>商户名称</dt><dd>{query.data.name}</dd>
            <dt>状态</dt><dd><Badge tone={query.data.status === 'ACTIVE' ? 'good' : 'danger'}>{query.data.status === 'ACTIVE' ? '正常' : '冻结'}</Badge></dd>
            <dt>版本</dt><dd className="mono">{query.data.version}</dd>
          </dl>
          <StackForm
            onSubmit={(event) => {
              event.preventDefault()
              if (!reason.trim()) return
              setConfirmOpen(true)
            }}
            actions={<Button tone={nextStatus === 'FROZEN' ? 'danger' : 'primary'} type="submit" aria-haspopup="dialog" loading={change.isPending} disabled={!reason.trim()}>{query.data.status === 'FROZEN' ? '恢复接单' : '冻结新订单'}</Button>}
          >
            <Field label="原因"><input required value={reason} onChange={(event) => setReason(event.target.value)} maxLength={255} /></Field>
          </StackForm>
        </Panel>
      ) : null}
      {missing ? (
        <Panel>
          <h2>创建商户</h2>
          <StackForm onSubmit={submitCreate} actions={<Button tone="primary" type="submit" loading={create.isPending}>创建</Button>}>
            <Field label="商户号"><input value={merchantNo} onChange={(event) => setMerchantNo(event.target.value)} maxLength={64} required /></Field>
            <Field label="商户名称"><input value={name} onChange={(event) => setName(event.target.value)} maxLength={200} required /></Field>
          </StackForm>
        </Panel>
      ) : null}
      {confirmOpen && query.data ? (
        <ConfirmDialog
          title={nextStatus === 'FROZEN' ? '冻结新订单' : '恢复接单'}
          description={`商户 ${query.data.name}（${query.data.merchantNo}），当前版本 ${query.data.version}。状态变更影响新订单受理。`}
          confirmLabel={nextStatus === 'FROZEN' ? '确认冻结' : '确认恢复'}
          tone={nextStatus === 'FROZEN' ? 'danger' : 'primary'}
          pending={change.isPending}
          onClose={() => setConfirmOpen(false)}
          onConfirm={() => change.mutate({
            expectedVersion: query.data.version,
            targetStatus: nextStatus,
            reason: reason.trim(),
          })}
        ><MutationBanner error={change.error} /></ConfirmDialog>
      ) : null}
    </div>
  )
}
