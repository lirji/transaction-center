import { useId, useState, type ComponentProps, type FormEvent } from 'react'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Select } from './Select'
import { Button, CursorPager, PagePager } from './ui'
import { pageSizes } from '../shared/ui/usePageSize'
import { formatBeijingTime } from '../shared/ui/display'
import type { NumberedPageMetadata } from '../shared/api/schemas'

const integer = new Intl.NumberFormat('zh-CN')

function JumpToPage({ page, totalPages, onPage }: {
  page: number
  totalPages: number
  onPage: (page: number) => void
}) {
  const [text, setText] = useState('')
  const [invalid, setInvalid] = useState(false)
  const errorId = useId()

  function jump(event: FormEvent) {
    event.preventDefault()
    const value = Number(text)
    if (!/^[0-9]+$/.test(text) || !Number.isSafeInteger(value) || value < 1 || value > totalPages) {
      setInvalid(true)
      return
    }
    setText('')
    setInvalid(false)
    if (value !== page) onPage(value)
  }

  return <form className="pager-jump" onSubmit={jump}>
    <div className="pager-jump-field">
      <input aria-label="指定页码" inputMode="numeric" placeholder={String(page)} value={text}
        aria-invalid={invalid || undefined} aria-describedby={invalid ? errorId : undefined}
        onChange={(event) => { setText(event.target.value); setInvalid(false) }} />
      <Button type="submit">跳转</Button>
    </div>
    {invalid && <span id={errorId} role="alert">请输入 1 至 {integer.format(totalPages)} 的页码</span>}
  </form>
}

export function NumberedPager({ result, onPage, onPageSize }: {
  result: NumberedPageMetadata
  onPage: (page: number) => void
  onPageSize: (size: number) => void
}) {
  const { page, totalPages, totalCount, pageSize, observedAt } = result
  const pageChoices = [...new Set([1, page - 2, page - 1, page, page + 1, page + 2, totalPages])]
    .filter((option) => option >= 1 && option <= totalPages)
    .sort((a, b) => a - b)

  return (
    <nav className="pager" aria-label="结果分页">
      <div className="pager-meta" aria-live="polite">
        <span>共 {integer.format(totalCount)} 条</span>
        <span>第 {totalPages ? integer.format(page) : '0'} / {integer.format(totalPages)} 页</span>
        <span>读取于 {formatBeijingTime(observedAt)}（北京时间）</span>
      </div>
      <div className="pager-actions">
        <Select className="pager-select" aria-label="每页条数" value={String(pageSize)}
          onChange={(value) => onPageSize(Number(value))}
          options={pageSizes.map((size) => ({ value: String(size), label: `${size} 条 / 页` }))} />
        <Button disabled={page <= 1} onClick={() => onPage(page - 1)}><ChevronLeft size={14} aria-hidden="true" />上一页</Button>
        {totalPages > 1 && <Select className="pager-select" aria-label="选择页码" value={String(page)}
          onChange={(value) => onPage(Number(value))}
          options={pageChoices.map((option) => ({ value: String(option), label: `第 ${integer.format(option)} 页` }))} />}
        <Button disabled={page >= totalPages} onClick={() => onPage(page + 1)}>下一页<ChevronRight size={14} aria-hidden="true" /></Button>
        {totalPages > 1 && <JumpToPage key={page} page={page} totalPages={totalPages} onPage={onPage} />}
      </div>
    </nav>
  )
}

type NumberedNavigation = {
  numbered?: NumberedPageMetadata
  onNumber: (page: number) => void
}

export function NumberedPagePager({ numbered, onNumber, ...legacy }: NumberedNavigation & ComponentProps<typeof PagePager>) {
  return numbered && legacy.onPageSize
    ? <NumberedPager result={numbered} onPage={onNumber} onPageSize={legacy.onPageSize} />
    : <PagePager {...legacy} />
}

export function NumberedCursorPager<Row>({ numbered, onNumber, ...legacy }:
  NumberedNavigation & Parameters<typeof CursorPager<Row>>[0]) {
  return numbered && legacy.onPageSize
    ? <NumberedPager result={numbered} onPage={onNumber} onPageSize={legacy.onPageSize} />
    : <CursorPager {...legacy} />
}
