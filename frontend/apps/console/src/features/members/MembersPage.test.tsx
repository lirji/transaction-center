import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MembersPage } from './MembersPage'
import { renderWithApp } from '../../test/render'
import { TRADE_ADMIN, TRADE_ADMIN_EVIDENCE_READ } from '../../shared/auth/auth-context'

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('MembersPage', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders members from the admin API', async () => {
    vi.mocked(fetch).mockResolvedValue(json([{
      memberId: '11',
      displayName: '林若',
      status: 'ACTIVE',
      registeredAt: '2026-01-01T00:00:00Z',
      everPaid: false,
      version: 2,
    }]))
    renderWithApp(<MembersPage />)
    expect(await screen.findByText('林若')).toBeInTheDocument()
    expect(screen.getByText('11')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '冻结' })).toBeInTheDocument()
  })

  it('submits freeze with expectedVersion and If-Match', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockImplementation(async (input, init) => {
      if (String(input).includes(':change-status') && (init as RequestInit | undefined)?.method === 'POST') {
        return json({ resourceId: '11' })
      }
      return json([{
        memberId: '11',
        displayName: '林若',
        status: 'ACTIVE',
        registeredAt: '2026-01-01T00:00:00Z',
        everPaid: false,
        version: 2,
      }])
    })
    renderWithApp(<MembersPage />)
    await screen.findByText('林若')
    await userEvent.click(screen.getByRole('button', { name: '冻结' }))
    fireEvent.change(screen.getByLabelText('原因'), { target: { value: '风险核查' } })
    await userEvent.click(screen.getByRole('button', { name: '提交' }))
    await waitFor(() => {
      const post = fetchMock.mock.calls.find((call) => String(call[0]).includes(':change-status'))
      expect(post).toBeTruthy()
      const init = post?.[1] as RequestInit
      const headers = new Headers(init.headers)
      expect(headers.get('If-Match')).toBe('"2"')
      expect(headers.get('Idempotency-Key')).toBeTruthy()
      expect(JSON.parse(String(init.body))).toEqual({ expectedVersion: 2, targetStatus: 'FROZEN', reason: '风险核查' })
    })
  })

  it('hides eligibility without evidence scope', async () => {
    vi.mocked(fetch).mockResolvedValue(json([]))
    renderWithApp(<MembersPage />, { scopes: [TRADE_ADMIN] })
    await screen.findByText('会员')
    expect(screen.queryByText('资格评估')).not.toBeInTheDocument()
  })

  it('does not POST campaign eligibility without campaignStartAt', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockResolvedValue(json([]))
    renderWithApp(<MembersPage />, { scopes: [TRADE_ADMIN, TRADE_ADMIN_EVIDENCE_READ] })
    await screen.findByText('资格评估')
    await userEvent.type(screen.getByLabelText('memberId'), '11')
    await userEvent.selectOptions(screen.getByLabelText('policyVersion'), 'REGISTERED_DURING_CAMPAIGN_V1')
    await userEvent.click(screen.getByRole('button', { name: '评估' }))
    expect(await screen.findByText(/必须填写 campaignStartAt/)).toBeInTheDocument()
    expect(fetchMock.mock.calls.every((call) => !String(call[0]).includes('eligibility:query'))).toBe(true)
  })
})
