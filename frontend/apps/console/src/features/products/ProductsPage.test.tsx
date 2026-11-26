import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ProductsPage } from './ProductsPage'
import { renderWithApp } from '../../test/render'

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('ProductsPage', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('refreshes the list after creating a draft', async () => {
    let rows: unknown[] = []
    vi.mocked(fetch).mockImplementation(async (input, init) => {
      if (String(input).includes('/admin/products') && (init as RequestInit | undefined)?.method === 'POST') {
        rows = [{
          productId: '9',
          skuId: '1',
          name: '演示茶',
          status: 'DRAFT',
          version: 1,
          unitPrice: 199,
          currency: 'CNY',
          publishedRevision: null,
        }]
        return json({ resourceId: '9' })
      }
      return json(rows)
    })
    renderWithApp(<ProductsPage />)
    expect(await screen.findByText('没有商品')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('商品编号'), 'P-1')
    await userEvent.type(screen.getByLabelText('商品名称'), '演示茶')
    await userEvent.type(screen.getByLabelText('主 SKU 编码'), 'SKU-1')
    await userEvent.type(screen.getByLabelText(/单价/), '199')
    await userEvent.click(screen.getByRole('button', { name: '创建' }))
    expect(await screen.findByText('演示茶')).toBeInTheDocument()
    await waitFor(() => {
      expect(vi.mocked(fetch).mock.calls.some((call) => (call[1] as RequestInit | undefined)?.method === 'POST')).toBe(true)
    })
  })
})
