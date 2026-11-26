import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PaymentChannelsPage } from './PaymentChannelsPage'
import { renderWithApp } from '../../test/render'

function json(data: unknown) {
  return new Response(JSON.stringify(data), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

describe('PaymentChannelsPage', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('rejects a lowercase channelCode before calling the API', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockResolvedValue(json([]))
    renderWithApp(<PaymentChannelsPage />)
    await screen.findByText('没有渠道')
    await userEvent.type(screen.getByLabelText('渠道编码'), 'wechat')
    await userEvent.type(screen.getByLabelText('渠道商户号'), 'mch')
    await userEvent.type(screen.getByLabelText('凭据引用'), 'secret')
    await userEvent.type(screen.getByLabelText('回跳配置编号'), 'ret-1')
    await userEvent.type(screen.getByLabelText('回跳 HTTPS 地址'), 'https://pay.example/return')
    await userEvent.click(screen.getByRole('button', { name: '登记' }))
    expect(await screen.findByText(/channelCode 须为/)).toBeInTheDocument()
    expect(fetchMock.mock.calls.some((call) => (call[1] as RequestInit | undefined)?.method === 'POST')).toBe(false)
  })
})
