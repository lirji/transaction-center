import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ReceiptsPage } from './ReceiptsPage'
import { renderWithApp } from '../../test/render'
import { adminPage, json } from '../../test/admin-fixtures'

describe('ReceiptsPage', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('does not request when abnormalOnly conflicts with NORMAL', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockImplementation(async (input) => /\/admin\/numbered-(pages|operations)\//.test(String(input)) ? json({}, 404) : json(adminPage([])))
    renderWithApp(<ReceiptsPage />, { route: '/receipts' })
    await userEvent.click(screen.getByRole('combobox', { name: '实收分类' }))
    await userEvent.click(screen.getByRole('option', { name: '正常实收' }))
    await userEvent.click(screen.getByRole('combobox', { name: '异常范围' }))
    await userEvent.click(screen.getByRole('option', { name: '仅异常' }))
    await userEvent.click(screen.getByRole('button', { name: '查询' }))
    expect(await screen.findByText(/互斥/)).toBeInTheDocument()
    await waitFor(() => {
      expect(fetchMock.mock.calls.every((call) => !String(call[0]).includes('disposition=NORMAL') || !String(call[0]).includes('abnormalOnly=true'))).toBe(true)
    })
  })
})
