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

  it('jumps using the last numeric cursor and can return from a terminal page', async () => {
    const channel = (id: number) => ({
      channelId: String(id),
      merchantNo: 'M-1',
      channelCode: `CHANNEL_${id}`,
      channelMerchantNo: 'provider-merchant',
      currency: 'CNY',
      returnUrlId: 'ret-1',
      status: 'ACTIVE',
      version: 1,
    })
    vi.mocked(fetch).mockImplementation(async (input) => {
      return json(String(input).includes('after=20')
        ? [channel(21)]
        : Array.from({ length: 20 }, (_, index) => channel(index + 1)))
    })
    renderWithApp(<PaymentChannelsPage />)
    expect(await screen.findByText('CHANNEL_1')).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('跳转页码'), screen.getByRole('option', { name: '第 2 页' }))
    expect(await screen.findByText('CHANNEL_21')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '下一页' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: '上一页' }))
    expect(await screen.findByText('CHANNEL_1')).toBeInTheDocument()
  })
})
