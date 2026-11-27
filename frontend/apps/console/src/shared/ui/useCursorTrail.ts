import { useState } from 'react'
import { hashKey } from '@tanstack/react-query'
import { useAuth } from '../auth/useAuth'

type Cursor = string | number
export type CursorScope = readonly [list: string, ...criteria: unknown[]]

type Trail<T extends Cursor> = {
  key: string
  criteria: string
  cursors: T[]
}

function readTrail<T extends Cursor>(key: string, first: T): Trail<T> | undefined {
  try {
    const raw = sessionStorage.getItem(key)
    if (raw === null) return undefined
    const saved = JSON.parse(raw) as Partial<Trail<T>>
    if (saved?.key === key && typeof saved.criteria === 'string' && Array.isArray(saved.cursors) &&
      saved.cursors.every((cursor) => typeof cursor === typeof first)) {
      return { key, criteria: saved.criteria, cursors: saved.cursors }
    }
  } catch {
    // 浏览器禁用会话存储时，仍可在当前页面使用内存中的分页路径。
  }
  return undefined
}

export function useCursorTrail<T extends Cursor>(scope: CursorScope, first: T, after: T, next: T | undefined, readComplete: boolean) {
  const { tenantId, subject } = useAuth()
  const key = 'trade.console.pager:' + hashKey([tenantId, subject, scope[0]])
  const criteria = hashKey(scope)
  const [stored, setStored] = useState(() => readTrail(key, first))
  const saved = stored?.key === key ? stored : readTrail(key, first)
  const remembered = saved?.criteria === criteria ? saved.cursors : []
  const index = remembered.indexOf(after)
  const position = Math.max(0, index)
  // 已读取的下一游标变更时，之后的页码已不对应当前排序结果。
  const trail = index === -1 ? [after]
    : readComplete && remembered[index + 1] !== next ? remembered.slice(0, index + 1)
    : remembered
  const pageNumber = trail[0] === first ? position + 1 : undefined
  const forward = next !== undefined && next !== first && !trail.slice(0, position + 1).includes(next) ? next : undefined
  const choices = forward !== undefined && trail[position + 1] !== forward ? [...trail, forward] : trail

  function remember(cursors: T[]) {
    const value = { key, criteria, cursors }
    setStored(value)
    try {
      sessionStorage.setItem(key, JSON.stringify(value))
    } catch {
      // 存储不可用时，保留当前已挂载分页组件的导航能力。
    }
  }

  return { trail, choices, position, pageNumber, forward, remember }
}
