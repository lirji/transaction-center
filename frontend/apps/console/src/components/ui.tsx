import { cloneElement, isValidElement, useContext, useEffect, useId, useLayoutEffect, useRef, useState, type ButtonHTMLAttributes, type FormEvent, type ReactElement, type ReactNode, type TableHTMLAttributes } from 'react'
import { createPortal } from 'react-dom'
import { Archive, ChevronLeft, ChevronRight, CircleAlert, CircleCheck, Columns3, Copy, Expand, Info, LoaderCircle, RefreshCw, X } from 'lucide-react'
import { useLocation } from 'react-router-dom'
import { rememberListScroll, restoreListScroll } from '../shared/ui/listScroll'
import { TableQueryContext } from '../shared/ui/tableQuery'
import { Select } from './Select'
import { pageSizes } from '../shared/ui/usePageSize'
import { FloatingPanel } from './FloatingPanel'
import { applyHiddenColumns, tableColumns } from './tableColumns'
import { useAuth } from '../shared/auth/useAuth'
import { nextCursor } from '../shared/api/keys'
import { formatBeijingTime } from '../shared/ui/display'
import { useCursorTrail, type CursorScope } from '../shared/ui/useCursorTrail'

export function PageHeader({ title, description, actions }: { title: string; description: string; actions?: ReactNode }) {
  return <section className="page-heading"><div><h1>{title}</h1><p>{description}</p></div>{actions && <div className="heading-actions">{actions}</div>}</section>
}

export function Button({ tone = 'secondary', className = '', onClick, disabled, loading, children, ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { loading?: boolean; tone?: 'primary' | 'secondary' | 'danger' | 'ghost' }) {
  return <button type={props.type ?? 'button'} className={`button ${tone} ${className}`} disabled={disabled || loading} aria-busy={loading || undefined} {...props}
    onClick={(event) => {
      // 部分浏览器点击按钮时不会聚焦，先标记弹窗关闭后应返回的入口。
      if (props['aria-haspopup'] === 'dialog') event.currentTarget.focus({ preventScroll: true })
      onClick?.(event)
    }}>{loading && <LoaderCircle className="control-spinner" size={14} aria-hidden="true" />}{children}</button>
}

export function Badge({ tone = 'neutral', children }: { tone?: 'good' | 'warn' | 'danger' | 'neutral' | 'info'; children: ReactNode }) {
  return <span className={`status ${tone}`}>{children}</span>
}

export function Panel({ className = '', children }: { className?: string; children: ReactNode }) {
  return <section className={`panel ${className}`}>{children}</section>
}

export function PanelHeader({ eyebrow, title, aside }: { eyebrow?: string; title: string; aside?: ReactNode }) {
  return <div className="panel-head"><div>{eyebrow && <p className="eyebrow">{eyebrow}</p>}<h2>{title}</h2></div>{aside}</div>
}

export function StateBanner({ tone, title, detail }: { tone: 'loading' | 'error' | 'warn' | 'success' | 'info'; title: string; detail: string }) {
  const Icon = tone === 'loading' ? LoaderCircle : tone === 'success' ? CircleCheck : tone === 'info' ? Info : CircleAlert
  return <div className={`state-banner ${tone}`} role={tone === 'error' ? 'alert' : 'status'}><Icon size={16} aria-hidden="true" /><div><strong>{title}</strong><span>{detail}</span></div></div>
}

export function Modal({ title, description, children, onClose, pending, variant = 'form' }: {
  title: string
  description?: string
  children: ReactNode
  onClose: () => void
  pending?: boolean
  variant?: 'form' | 'confirm' | 'search' | 'table' | 'drawer'
}) {
  const card = useRef<HTMLDialogElement>(null)
  const id = useId()

  useEffect(() => {
    const dialog = card.current
    if (!dialog) return
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
    if (typeof dialog.showModal === 'function') dialog.showModal()
    else dialog.setAttribute('open', '')
    dialog.querySelector<HTMLElement>('[data-initial-focus]')?.focus()
    return () => {
      if (typeof dialog.close === 'function') dialog.close()
      else dialog.removeAttribute('open')
      if (trigger?.isConnected) trigger.focus({ preventScroll: true })
    }
  }, [])

  return createPortal(
    <dialog ref={card} className={`modal-card modal-${variant}`} aria-labelledby={id}
      aria-describedby={description ? `${id}-description` : undefined} aria-busy={pending || undefined}
      onCancel={(event) => {
        event.preventDefault()
        if (!pending) onClose()
      }}
      onClick={(event) => {
        if (variant === 'search' && event.target === event.currentTarget) onClose()
      }}>
      <header>
        <div><h2 id={id}>{title}</h2>{description && <p id={`${id}-description`}>{description}</p>}</div>
        <button type="button" className="icon-button" disabled={pending} onClick={onClose} aria-label="关闭"><X size={18} /></button>
      </header>
      {children}
    </dialog>,
    document.body,
  )
}

type TablePreferences = { hidden: string[]; compact: boolean }

function readTablePreference(key: string): TablePreferences {
  try {
    const value = JSON.parse(localStorage.getItem(key) ?? 'null') as TablePreferences | null
    if (value && typeof value.compact === 'boolean' && Array.isArray(value.hidden) && value.hidden.every((item) => typeof item === 'string')) return value
  } catch {
    // 存储不可用时继续使用当前页面的默认展示方式。
  }
  return { hidden: [], compact: false }
}

export function DataTable({ label, className = '', children, tools = true, onRefresh, refreshing, ...props }: TableHTMLAttributes<HTMLTableElement> & {
  label: string
  tools?: boolean
  onRefresh?: () => void
  refreshing?: boolean
}) {
  const context = useContext(TableQueryContext)
  const query = onRefresh ? { refresh: onRefresh, refreshing } : context
  const { pathname, search, state } = useLocation()
  const { tenantId, subject } = useAuth()
  const preferenceKey = `trade.console.table:${JSON.stringify([tenantId, subject, label])}`
  const restore = state?.restoreListScroll === true
  const scrollKey = `${preferenceKey}:${pathname}${search}`
  const [preferences, setPreferences] = useState(() => readTablePreference(preferenceKey))
  const [settings, setSettings] = useState(false)
  const [expanded, setExpanded] = useState(false)
  const options = useRef<HTMLButtonElement>(null)
  const scroller = useRef<HTMLDivElement>(null)
  const scroll = useRef(0)
  const columns = tools ? tableColumns(children) : []
  const available = columns.filter(({ index, label: name }) => index > 0 && name && name !== '操作')
  const hidden = new Set(available.filter(({ key }) => preferences.hidden.includes(key)).map(({ index }) => index))
  const fixedAction = columns.at(-1)?.label === '操作'

  useLayoutEffect(() => {
    let left = 0
    try { left = Number(sessionStorage.getItem(scrollKey)) || 0 } catch { /* 允许使用不保存滚动位置的浏览器 */ }
    if (scroller.current) scroller.current.scrollLeft = left
    scroll.current = left
  }, [scrollKey, expanded])

  useLayoutEffect(() => {
    // 等表格挂载后再恢复文档滚动，避免加载占位高度把目标位置截断。
    if (restore) restoreListScroll(`${scrollKey}:window`)
  }, [restore, scrollKey])

  function save(next: TablePreferences) {
    setPreferences(next)
    try { localStorage.setItem(preferenceKey, JSON.stringify(next)) } catch { /* 当前会话仍可操作 */ }
  }

  const content = (
    <div ref={scroller} className={`table-scroll ${tools ? 'table-scroll-managed' : ''}`} role="region" aria-label={label} tabIndex={0}
      onClickCapture={(event) => {
        if (event.target instanceof Element && event.target.closest('a[href]')) rememberListScroll(`${scrollKey}:window`)
      }}
      onScroll={(event) => {
        const left = event.currentTarget.scrollLeft
        if (scroll.current === left) return
        scroll.current = left
        try { sessionStorage.setItem(scrollKey, String(left)) } catch { /* 存储关闭时使用正常滚动 */ }
      }}>
      <table className={`data-table ${className} ${preferences.compact && tools ? 'data-table-compact' : ''} ${fixedAction ? 'pin-actions' : ''}`}
        {...props} style={hidden.size ? { ...props.style, minWidth: Math.max(460, (columns.length - hidden.size) * 105) } : props.style}>
        {applyHiddenColumns(children, hidden)}
      </table>
    </div>
  )

  return (
    <div className="table-view">
      {tools && (
        <div className="table-tools" role="group" aria-label={`${label}工具`}>
          {query && <Button tone="ghost" loading={query.refreshing} onClick={query.refresh}><RefreshCw size={14} aria-hidden="true" />刷新</Button>}
          <button ref={options} type="button" className="button ghost" aria-expanded={settings}
            onClick={() => setSettings(!settings)}><Columns3 size={14} aria-hidden="true" />显示设置</button>
          <Button tone="ghost" aria-haspopup="dialog" onClick={() => { setSettings(false); setExpanded(true) }}><Expand size={14} aria-hidden="true" />放大表格</Button>
          {settings && <FloatingPanel anchor={options} width={230} className="table-settings" onClose={() => setSettings(false)}>
            <div onKeyDown={(event) => {
              if (event.key === 'Escape') {
                event.preventDefault()
                setSettings(false)
                options.current?.focus()
              }
            }}>
              <label><input data-popup-focus type="checkbox" checked={preferences.compact}
                onChange={(event) => save({ ...preferences, compact: event.target.checked })} />紧凑行高</label>
              {available.length > 0 && <fieldset>
                <legend>显示列</legend>
                {available.map(({ key, label: name }) => <label key={key}><input type="checkbox" checked={!preferences.hidden.includes(key)}
                  onChange={(event) => save({ ...preferences, hidden: event.target.checked
                    ? preferences.hidden.filter((value) => value !== key) : [...preferences.hidden, key] })} />{name}</label>)}
                <Button tone="ghost" onClick={() => save({ ...preferences, hidden: [] })}>恢复全部列</Button>
              </fieldset>}
            </div>
          </FloatingPanel>}
        </div>
      )}
      {expanded ? <Modal variant="table" title={label} onClose={() => setExpanded(false)}>{content}</Modal> : content}
    </div>
  )
}

export function CopyValue({ value, name, children }: { value: string; name: string; children?: ReactNode }) {
  const [feedback, setFeedback] = useState('')
  return <span className="copy-value">
    {children ?? <span className="mono">{value}</span>}
    <button type="button" className="copy-button" aria-label={`复制${name}`} title={feedback || `复制${name}`}
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(value)
          setFeedback('已复制')
        } catch {
          setFeedback('复制失败，可选中文本手动复制')
        }
      }}>{feedback === '已复制' ? <CircleCheck size={14} /> : <Copy size={14} />}</button>
    <span className="sr-only" role="status">{feedback}</span>
  </span>
}

export function EmptyState({ title, detail, action }: { title: string; detail: string; action?: ReactNode }) {
  return <div className="empty-state"><span className="empty-icon"><Archive size={19} aria-hidden="true" /></span><h3>{title}</h3><p>{detail}</p>{action}</div>
}

export function Field({ label, hint, error, children, wide }: { label: string; hint?: string; error?: string; wide?: boolean; children: ReactNode }) {
  const id = useId()
  const describedBy = error || hint ? `${id}-desc` : undefined
  const element = isValidElement(children) ? children as ReactElement<Record<string, unknown>> : null
  const control = element
    ? cloneElement(element, {
        id,
        'aria-invalid': error ? true : element.props['aria-invalid'],
        'aria-describedby': describedBy,
      })
    : children
  return (
    <div className={`field${wide ? ' field-wide' : ''}`}>
      <div className="field-label">
        <label htmlFor={id}>{label}</label>
        {element?.props.required ? <span className="required-mark" aria-hidden="true">*</span> : null}
      </div>
      {control}
      {error ? <small id={describedBy}>{error}</small> : hint ? <small id={describedBy} className="field-hint">{hint}</small> : null}
    </div>
  )
}

type PagerNavigation<T> = {
  after: T
  scope: CursorScope
  onPage: (cursor: T) => void
  pageSize?: number
  onPageSize?: (size: number) => void
}

export function CursorPager<Row>({ pageSize, rows, getCursor, ...navigation }: PagerNavigation<number> & {
  pageSize: number
  rows?: readonly Row[]
  getCursor: (row: Row) => string
}) {
  const last = rows?.at(-1)
  const next = nextCursor(last === undefined ? undefined : getCursor(last), rows?.length ?? 0, pageSize)
  return <Pager first={0} count={rows?.length} next={next} pageSize={pageSize} {...navigation} />
}

type CursorResult = { items: readonly unknown[] } & (
  { nextAfter: string | null; hasMore: boolean; observedAt?: string }
  | { nextCursor?: string | null }
)

export function PagePager({ result, ...navigation }: PagerNavigation<string> & { result?: CursorResult }) {
  const next = result && ('hasMore' in result ? (result.hasMore ? result.nextAfter : null) : result.nextCursor)
  const observedAt = result && 'observedAt' in result ? result.observedAt : undefined
  return <Pager first="0" next={next || undefined} count={result?.items.length} observedAt={observedAt} {...navigation} />
}

function Pager<T extends string | number>({ first, after, scope, next, count, observedAt, onPage, pageSize, onPageSize }: PagerNavigation<T> & {
  first: T
  next?: T
  count?: number
  observedAt?: string
}) {
  const { trail, choices, position, pageNumber, forward, remember } = useCursorTrail([...scope, pageSize], first, after, next, count !== undefined)
  const previous = trail[position - 1]
  if (count === undefined && after === first) return null

  function change(cursor: T, path: T[]) {
    if (cursor === after) return
    remember(path)
    onPage(cursor)
  }

  return (
    <nav className="pager" aria-label="结果分页">
      <div className="pager-meta" aria-live="polite">
        {pageNumber !== undefined && <span>第 {pageNumber} 页</span>}
        {count !== undefined && <span>本页 {count} 条</span>}
        {observedAt && <span>读取于 {formatBeijingTime(observedAt)}（北京时间）</span>}
      </div>
      <div className="pager-actions">
        {pageSize !== undefined && onPageSize && <Select className="pager-select" aria-label="每页条数"
          value={String(pageSize)} onChange={(value) => onPageSize(Number(value))}
          options={pageSizes.map((size) => ({ value: String(size), label: `${size} 条 / 页` }))} />}
        <Button disabled={after === first} onClick={() => change(first, trail[0] === first ? trail : [first])}>回到首页</Button>
        <Button disabled={previous === undefined} onClick={() => {
          if (previous !== undefined) change(previous, trail)
        }}><ChevronLeft size={14} aria-hidden="true" />上一页</Button>
        {pageNumber !== undefined && choices.length > 1 && (
          <Select className="pager-select" aria-label="跳转页码" value={String(position)} searchable
            onChange={(value) => {
              const cursor = choices[Number(value)]
              if (cursor !== undefined) change(cursor, choices)
            }}
            options={choices.map((_, index) => ({ value: String(index), label: `第 ${index + 1} 页` }))} />
        )}
        <Button disabled={forward === undefined} onClick={() => {
          if (forward !== undefined) change(forward, choices)
        }}>下一页<ChevronRight size={14} aria-hidden="true" /></Button>
      </div>
    </nav>
  )
}

export function ConfirmDialog({
  title,
  description,
  confirmLabel,
  tone = 'danger',
  pending,
  onConfirm,
  onClose,
  children,
}: {
  title: string
  description?: string
  confirmLabel: string
  tone?: 'primary' | 'danger'
  pending?: boolean
  onConfirm: () => void
  onClose: () => void
  children?: ReactNode
}) {
  return (
    <Modal title={title} description={description} onClose={onClose} variant="confirm" pending={pending}>
      <div className="danger-confirm">
        {children}
        <div className="modal-actions">
          <Button onClick={onClose} disabled={pending} data-initial-focus>取消</Button>
          <Button tone={tone} onClick={onConfirm} loading={pending}>{pending ? '正在提交…' : confirmLabel}</Button>
        </div>
      </div>
    </Modal>
  )
}

export function StackForm({ onSubmit, children, actions }: { onSubmit: (event: FormEvent) => void; children: ReactNode; actions: ReactNode }) {
  return (
    <form className="stack-form" onSubmit={onSubmit}>
      {children}
      <div className="modal-actions">{actions}</div>
    </form>
  )
}
