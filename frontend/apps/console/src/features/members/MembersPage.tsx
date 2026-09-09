import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Badge, Button, CursorPager, Field, Modal, PageHeader, Panel, StackForm, StateBanner } from '../../components/ui'
import { api, type Member } from '../../shared/api/client'
import { MutationBanner, QueryGate } from '../../shared/ui/QueryGate'
import { invalidateIfVersionConflict } from '../../shared/ui/invalidateIfVersionConflict'
import { isVersionConflict } from '../../shared/api/client'
import { useAuth } from '../../shared/auth/useAuth'
import { TRADE_ADMIN_EVIDENCE_READ } from '../../shared/auth/auth-context'
import { FormValidationError, parseMemberId } from '../../shared/api/validate'
import { dash } from '../../shared/ui/display'

export function MembersPage() {
  const auth = useAuth()
  const canEvidence = auth.hasScope(TRADE_ADMIN_EVIDENCE_READ)
  const [after, setAfter] = useState(0)
  const [selected, setSelected] = useState<Member>()
  const [reason, setReason] = useState('')
  const [formError, setFormError] = useState('')
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: ['members', after], queryFn: () => api.members(after) })
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

  return (
    <div className="workspace">
      <PageHeader
        eyebrow="Members"
        title="会员"
        description="列表不含认证主体。冻结不阻止合法退款入账，也不会改 everPaid。冻结后改资料或新增同意会返回 422 MEMBER_FROZEN。"
      />
      <MutationBanner error={change.error} success={change.isSuccess ? `已提交状态变更，resourceId=${change.data?.resourceId}` : undefined} />
      <Panel className="table-panel">
        <QueryGate query={query} emptyTitle="没有会员" emptyDetail="当前租户没有返回会员。若你期望看到演示数据，JWT tenant_id 必须是 900001。">
          {(rows) => (
            <>
              <table className="data-table">
                <thead><tr><th>会员</th><th>状态</th><th>曾支付</th><th>版本</th><th /></tr></thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.memberId}>
                      <td><strong>{row.displayName}</strong><small className="mono">{row.memberId}</small></td>
                      <td><Badge tone={row.status === 'FROZEN' ? 'danger' : 'good'}>{row.status}</Badge></td>
                      <td>{row.everPaid ? '是' : '否'}</td>
                      <td className="mono">{row.version}</td>
                      <td>
                        <Button tone="ghost" onClick={() => { setSelected(row); setReason(''); setFormError(''); change.reset() }}>
                          {row.status === 'FROZEN' ? '解冻' : '冻结'}
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <CursorPager after={after} pageSize={20} lastId={rows.at(-1)?.memberId} length={rows.length} onReset={() => setAfter(0)} onNext={setAfter} />
            </>
          )}
        </QueryGate>
      </Panel>
      {selected && (
        <Modal
          title={selected.status === 'FROZEN' ? '解冻会员' : '冻结会员'}
          description={`将提交 expectedVersion=${selected.version} 与 If-Match。冲突时请关闭后重新读取，不要换键盲重试。`}
          onClose={() => setSelected(undefined)}
        >
          <form onSubmit={(event) => {
            event.preventDefault()
            setFormError('')
            if (!reason.trim()) {
              setFormError('必须填写原因')
              return
            }
            const targetStatus = selected.status === 'FROZEN' ? 'ACTIVE' : 'FROZEN'
            change.mutate({ member: selected, targetStatus, reason: reason.trim() })
          }}>
            {isVersionConflict(change.error) ? <StateBanner tone="warn" title="请重新读取" detail="版本已变化。关闭对话框并刷新列表后再提交新动作。" /> : null}
            <Field label="原因" error={formError}>
              <textarea value={reason} onChange={(event) => setReason(event.target.value)} maxLength={255} required />
            </Field>
            <div className="modal-actions">
              <Button onClick={() => setSelected(undefined)}>取消</Button>
              <Button tone="primary" type="submit" disabled={change.isPending}>提交</Button>
            </div>
          </form>
        </Modal>
      )}
      {canEvidence ? <EligibilityPanel /> : null}
    </div>
  )
}

function EligibilityPanel() {
  const [memberId, setMemberId] = useState('')
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
      const campaign = campaignStartAt.trim() ? new Date(campaignStartAt) : undefined
      const evaluated = evaluatedAt.trim() ? new Date(evaluatedAt) : undefined
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
        <Field label="campaignStartAt">
          <input type="datetime-local" value={campaignStartAt} onChange={(event) => setCampaignStartAt(event.target.value)} />
        </Field>
        <Field label="evaluatedAt">
          <input type="datetime-local" value={evaluatedAt} onChange={(event) => setEvaluatedAt(event.target.value)} />
        </Field>
      </StackForm>
      {query.data ? (
        <dl className="kv">
          <dt>结果</dt><dd><Badge>{query.data.status}</Badge></dd>
          <dt>eligible</dt><dd>{query.data.evidence ? (query.data.evidence.eligible ? '是' : '否') : '—'}</dd>
          <dt>原因</dt><dd className="mono">{query.data.evidence?.reasonCodes.join(', ') || '—'}</dd>
          <dt>首笔支付单</dt><dd className="mono">{dash(query.data.evidence?.firstPaidOrderNo)}</dd>
          <dt>评估时刻</dt><dd className="mono">{dash(query.data.evidence?.evaluatedAt)}</dd>
        </dl>
      ) : null}
    </Panel>
  )
}
