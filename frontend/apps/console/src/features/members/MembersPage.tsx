import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Badge, Button, Field, Modal, PageHeader, PagePager, Panel, StackForm, StateBanner, DataTable } from '../../components/ui'
import { api, type Member } from '../../shared/api/client'
import { MutationBanner, QueryGate } from '../../shared/ui/QueryGate'
import { invalidateIfVersionConflict } from '../../shared/ui/invalidateIfVersionConflict'
import { isVersionConflict } from '../../shared/api/client'
import { useAuth } from '../../shared/auth/useAuth'
import { TRADE_ADMIN_EVIDENCE_READ } from '../../shared/auth/auth-context'
import { parseBeijingInput } from '../../shared/api/businessTime'
import { FormValidationError, parseMemberId, parseTimeWindow } from '../../shared/api/validate'
import { dash, formatBeijingTime } from '../../shared/ui/display'

import { EmptySelect, FilterBar, TimeRangeFields } from '../../shared/ui/FilterBar'
import { isoFromSearch, useAdminListSearch } from '../../shared/ui/useAdminListSearch'

const MEMBER_FROZEN = 'FROZEN'
const MEMBER_ACTIVE = 'ACTIVE'
const MEMBER_STATUS_LABELS: Record<string, string> = { [MEMBER_ACTIVE]: '正常', [MEMBER_FROZEN]: '冻结' }

export function MembersPage() {
  const auth = useAuth()
  const canEvidence = auth.hasScope(TRADE_ADMIN_EVIDENCE_READ)
  const { params, after, apply, setAfter } = useAdminListSearch()
  const filters = {
    memberId: params.get('memberId') || undefined,
    status: params.get('status') || undefined,
    registeredFrom: params.get('registeredFrom') || undefined,
    registeredTo: params.get('registeredTo') || undefined,
  }
  const [memberFilter, setMemberFilter] = useState(filters.memberId ?? '')
  const [statusFilter, setStatusFilter] = useState(filters.status ?? '')
  const [registeredFrom, setRegisteredFrom] = useState(() => isoFromSearch(params, 'registeredFrom'))
  const [registeredTo, setRegisteredTo] = useState(() => isoFromSearch(params, 'registeredTo'))
  const [filterError, setFilterError] = useState('')
  const [evidenceMember, setEvidenceMember] = useState('')
  const [selected, setSelected] = useState<Member>()
  const [reason, setReason] = useState('')
  const [formError, setFormError] = useState('')
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: ['members', after, filters], queryFn: () => api.members({ after, ...filters }) })
  const change = useMutation({
    mutationFn: (payload: { member: Member; targetStatus: 'ACTIVE' | 'FROZEN'; reason: string }) =>
      api.changeMemberStatus(payload.member.memberId, {
        expectedVersion: payload.member.version,
        targetStatus: payload.targetStatus,
        reason: payload.reason,
      }),
    onSuccess: async () => {
      setSelected(undefined)
      setReason('')
      await queryClient.invalidateQueries({ queryKey: ['members'] })
    },
    onError: (error) => void invalidateIfVersionConflict(queryClient, error, ['members']),
  })

  function clearFilters() {
    setMemberFilter('')
    setStatusFilter('')
    setRegisteredFrom('')
    setRegisteredTo('')
    setFilterError('')
    apply({})
  }

  return (
    <div className="workspace">
      <PageHeader
        title="会员"
        description="查询会员注册与支付事实，按需冻结、解冻或评估资格。"
      />
      <MutationBanner error={change.error} success={change.isSuccess ? `已提交状态变更，resourceId=${change.data?.resourceId}` : undefined} />
      <FilterBar onReset={clearFilters} invalid={Boolean(filterError)} activeAdvanced={params.has('registeredFrom') || params.has('registeredTo') ? 1 : 0}
        advanced={<TimeRangeFields labelPrefix="注册" from={registeredFrom} to={registeredTo} onFrom={setRegisteredFrom} onTo={setRegisteredTo} />} onSubmit={() => {
        setFilterError('')
        try {
          const window = parseTimeWindow(registeredFrom, registeredTo)
          apply({
            memberId: memberFilter.trim() ? parseMemberId(memberFilter, '会员 ID') : undefined,
            status: statusFilter, registeredFrom: window?.createdFrom, registeredTo: window?.createdTo,
          })
        } catch (cause) {
          setFilterError(cause instanceof Error ? cause.message : '筛选无效')
        }
      }}>
        <Field label="会员 ID"><input value={memberFilter} onChange={(event) => setMemberFilter(event.target.value)} inputMode="numeric" /></Field>
        <Field label="会员状态">
          <EmptySelect value={statusFilter} onChange={setStatusFilter} options={Object.keys(MEMBER_STATUS_LABELS)}
            optionLabel={(value) => MEMBER_STATUS_LABELS[value]} />
        </Field>
      </FilterBar>
      {filterError ? <StateBanner tone="warn" title="筛选条件无效" detail={filterError} /> : null}
      <Panel className="table-panel">
        <h2 className="table-title">会员列表</h2>
        <QueryGate query={query} emptyTitle="没有会员" emptyDetail="当前条件没有匹配的会员。">
          {(rows) => (
            <>
              <DataTable label="会员列表" className="trade-list-table pin-key">
                <thead><tr><th>会员名称</th><th>会员 ID</th><th>会员状态</th><th className="time-cell">注册时间（北京时间）</th><th>曾确认支付</th><th>版本</th><th>操作</th></tr></thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.memberId}>
                      <td>{row.displayName}</td>
                      <td className="mono">{row.memberId}</td>
                      <td><Badge tone={row.status === MEMBER_FROZEN ? 'danger' : 'good'}>{MEMBER_STATUS_LABELS[row.status] ?? row.status}</Badge></td>
                      <td className="time-cell">{formatBeijingTime(row.registeredAt)}</td>
                      <td>{row.everPaid ? '是' : '否'}</td>
                      <td className="mono">{row.version}</td>
                      <td>
                        <Button tone="ghost" aria-haspopup="dialog" onClick={() => { setSelected(row); setReason(''); setFormError(''); change.reset() }}>
                          {row.status === MEMBER_FROZEN ? '解冻' : '冻结'}
                        </Button>
                        {canEvidence ? <Button tone="ghost" onClick={() => {
                          setEvidenceMember(row.memberId)
                          document.getElementById('member-eligibility')?.scrollIntoView({ block: 'start' })
                        }}>评估资格</Button> : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </DataTable>
              <PagePager count={rows.length} after={after} nextAfter={rows.at(-1)?.memberId ?? null} hasMore={rows.length === 20} onReset={() => setAfter('0')} onNext={setAfter} />
            </>
          )}
        </QueryGate>
      </Panel>
      {selected && (
        <Modal
          title={selected.status === MEMBER_FROZEN ? '解冻会员' : '冻结会员'}
          description={`${selected.displayName || selected.memberId} · 会员 ID ${selected.memberId}。请填写状态变更原因。`}
          pending={change.isPending}
          onClose={() => setSelected(undefined)}
        >
          <form onSubmit={(event) => {
            event.preventDefault()
            setFormError('')
            if (!reason.trim()) {
              setFormError('必须填写原因')
              return
            }
            const targetStatus = selected.status === MEMBER_FROZEN ? 'ACTIVE' : 'FROZEN'
            change.mutate({ member: selected, targetStatus, reason: reason.trim() })
          }}>
            {isVersionConflict(change.error) ? <StateBanner tone="warn" title="请重新读取" detail="版本已变化。关闭对话框并刷新列表后再提交新动作。" /> : <MutationBanner error={change.error} />}
            <Field label="原因" error={formError}>
              <textarea value={reason} onChange={(event) => setReason(event.target.value)} maxLength={255} required />
            </Field>
            <div className="modal-actions">
              <Button onClick={() => setSelected(undefined)} disabled={change.isPending}>取消</Button>
              <Button tone="primary" type="submit" disabled={change.isPending}>提交</Button>
            </div>
          </form>
        </Modal>
      )}
      {canEvidence ? <section id="member-eligibility" className="section-gap"><EligibilityPanel key={evidenceMember} initialMemberId={evidenceMember} /></section> : null}
    </div>
  )
}

function EligibilityPanel({ initialMemberId }: { initialMemberId: string }) {
  const [memberId, setMemberId] = useState(initialMemberId)
  const [policyVersion, setPolicyVersion] = useState<'REGISTERED_DURING_CAMPAIGN_V1' | 'NEVER_CONFIRMED_PAID_V1'>('NEVER_CONFIRMED_PAID_V1')
  const [campaignStartAt, setCampaignStartAt] = useState('')
  const [evaluatedAt, setEvaluatedAt] = useState('')
  const [formError, setFormError] = useState('')
  const query = useMutation({
    mutationFn: (payload: {
      memberId: string
      body: { policyVersion: 'REGISTERED_DURING_CAMPAIGN_V1' | 'NEVER_CONFIRMED_PAID_V1'; campaignStartAt?: string; evaluatedAt?: string }
    }) => api.queryMemberEligibility(payload.memberId, payload.body),
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    setFormError('')
    try {
      const id = parseMemberId(memberId)
      if (policyVersion === 'REGISTERED_DURING_CAMPAIGN_V1' && !campaignStartAt.trim()) {
        throw new FormValidationError('REGISTERED_DURING_CAMPAIGN_V1 必须填写 campaignStartAt')
      }
      const campaign = campaignStartAt.trim() ? new Date(parseBeijingInput(campaignStartAt)) : undefined
      const evaluated = evaluatedAt.trim() ? new Date(parseBeijingInput(evaluatedAt)) : undefined
      if (campaign && Number.isNaN(campaign.getTime())) throw new FormValidationError('campaignStartAt 无效')
      if (evaluated && Number.isNaN(evaluated.getTime())) throw new FormValidationError('evaluatedAt 无效')
      query.mutate({
        memberId: id,
        body: {
          policyVersion,
          campaignStartAt: campaign?.toISOString(),
          evaluatedAt: evaluated?.toISOString(),
        },
      })
    } catch (cause) {
      setFormError(cause instanceof Error ? cause.message : '输入无效')
    }
  }

  return (
    <Panel>
      <h2>资格评估</h2>
      <p>只读评估交易原生新客策略，不预占资格，也不是营销资格。</p>
      <MutationBanner error={query.error} />
      {formError ? <StateBanner tone="warn" title="未发送请求" detail={formError} /> : null}
      <StackForm
        onSubmit={submit}
        actions={<Button tone="primary" type="submit" disabled={query.isPending}>评估</Button>}
      >
        <Field label="memberId"><input value={memberId} onChange={(event) => setMemberId(event.target.value)} required /></Field>
        <Field label="policyVersion">
          <select value={policyVersion} onChange={(event) => setPolicyVersion(event.target.value as typeof policyVersion)}>
            <option value="NEVER_CONFIRMED_PAID_V1">NEVER_CONFIRMED_PAID_V1</option>
            <option value="REGISTERED_DURING_CAMPAIGN_V1">REGISTERED_DURING_CAMPAIGN_V1</option>
          </select>
        </Field>
        <Field label="活动开始时间（北京时间）">
          <input type="datetime-local" value={campaignStartAt} onChange={(event) => setCampaignStartAt(event.target.value)} />
        </Field>
        <Field label="评估时间（北京时间）">
          <input type="datetime-local" value={evaluatedAt} onChange={(event) => setEvaluatedAt(event.target.value)} />
        </Field>
      </StackForm>
      {query.data ? (
        <dl className="kv">
          <dt>结果</dt><dd><Badge>{query.data.status}</Badge></dd>
          <dt>eligible</dt><dd>{query.data.evidence ? (query.data.evidence.eligible ? '是' : '否') : '—'}</dd>
          <dt>原因</dt><dd className="mono">{query.data.evidence?.reasonCodes.join(', ') || '—'}</dd>
          <dt>首笔支付单</dt><dd className="mono">{dash(query.data.evidence?.firstPaidOrderNo)}</dd>
          <dt>评估时刻（北京时间）</dt><dd>{formatBeijingTime(query.data.evidence?.evaluatedAt)}</dd>
        </dl>
      ) : null}
    </Panel>
  )
}
