import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { OutboxPage } from './OutboxPage'
import { renderWithApp } from '../../test/render'

function json(data: unknown) {
  return new Response(JSON.stringify(data), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

describe('OutboxPage', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('refetches tasks after a successful replay', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockImplementation(async (input, init) => {
      const url = String(input)
      if (url.includes(':replay') && (init as RequestInit | undefined)?.method === 'POST') {
        return json({ eventId: 'evt-1', version: 4, replayCount: 2 })
      }
      if (url.includes('/tasks')) {
        return json([{
          id: '7',
          eventId: 'evt-1',
          referenceHash: 'h',
          status: 'REVIEW',
          attempts: 1,
          nextRunAt: '2026-01-01T00:00:00Z',
          lastError: 'timeout',
        }])
      }
      return json([{
        id: '5',
        eventId: 'evt-1',
        eventType: 'order.paid.v1',
        aggregateId: '1',
        status: 'REVIEW',
        lastError: 'timeout',
        firstFailureAt: null,
        lastFailureAt: null,
        publishedAt: null,
        publishedTopic: null,
        version: 3,
        attemptBase: 1,
        replayCount: 1,
        failureCount: 1,
        publishedPartition: null,
        publishedOffset: null,
      }])
    })
    renderWithApp(<OutboxPage />)
    expect(await screen.findByText('order.paid.v1')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '重放' }))
    await userEvent.type(screen.getByLabelText('原因'), '人工核对')
    await userEvent.type(screen.getByLabelText('工单引用'), 'T-1')
    await userEvent.click(screen.getByRole('button', { name: '确认重放' }))
    await waitFor(() => {
      const taskGets = fetchMock.mock.calls.filter((call) => String(call[0]).includes('/tasks'))
      expect(taskGets.length).toBeGreaterThan(1)
    })
  })
})
