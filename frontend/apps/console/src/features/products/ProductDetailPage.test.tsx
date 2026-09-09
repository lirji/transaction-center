import { screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ProductDetailPage } from './ProductDetailPage'
import { renderWithApp } from '../../test/render'

function json(data: unknown) {
  return new Response(JSON.stringify(data), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

const draft = {
  productId: '9',
  skuId: '1',
  name: '演示茶',
  status: 'DRAFT',
  version: 3,
  unitPrice: 199,
  currency: 'CNY',
  publishedRevision: null,
}

const published = { ...draft, status: 'PUBLISHED', publishedRevision: 1 }

describe('ProductDetailPage', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('allows adding and editing SKUs only while the product is a draft', async () => {
    vi.mocked(fetch).mockImplementation(async (input) => {
      const url = String(input)
      if (url.endsWith('/skus') || url.includes('/skus?')) {
        return json([{
          skuId: '1',
          skuNo: 'SKU-1',
          productId: '9',
          title: '演示茶',
          unitPrice: 199,
          currency: 'CNY',
          stockType: 'PHYSICAL',
          specification: { color: '绿' },
          revision: 1,
        }])
      }
      return json(draft)
    })
    renderWithApp(<ProductDetailPage />, { route: '/products/9', path: '/products/:id' })
    expect(await screen.findByRole('heading', { name: '追加 SKU' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '编辑' })).toBeInTheDocument()
  })

  it('hides SKU writes after publish', async () => {
    vi.mocked(fetch).mockImplementation(async (input) => {
      if (String(input).includes('/skus')) return json([])
      return json(published)
    })
    renderWithApp(<ProductDetailPage />, { route: '/products/9', path: '/products/:id' })
    expect(await screen.findByText('已发布快照不可改商品正文')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '追加 SKU' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '编辑' })).not.toBeInTheDocument()
  })
})
