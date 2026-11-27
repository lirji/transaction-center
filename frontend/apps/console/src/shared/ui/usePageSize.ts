import { useState } from 'react'
import { useAuth } from '../auth/useAuth'

export const pageSizes = [10, 20, 50, 100] as const

export function validPageSize(value: unknown, fallback: number) {
  const size = Number(value)
  return pageSizes.some((option) => option === size) ? size : fallback
}

export function usePageSize(scope: string, initial: number, onReset?: () => void) {
  const { tenantId, subject } = useAuth()
  const key = `trade.console.page-size:${JSON.stringify([tenantId, subject, scope])}`
  const [stored, setStored] = useState(() => {
    try { return validPageSize(localStorage.getItem(key), initial) } catch { return initial }
  })

  function setPageSize(value: number) {
    const limit = validPageSize(value, initial)
    setStored(limit)
    try { localStorage.setItem(key, String(limit)) } catch { /* 浏览器禁用存储时保留会话内选择 */ }
    onReset?.()
  }

  return [stored, setPageSize] as const
}
