import { cloneElement, isValidElement, useEffect, useId, useRef, type ButtonHTMLAttributes, type FormEvent, type ReactElement, type ReactNode, type TableHTMLAttributes } from 'react'
import { createPortal } from 'react-dom'
import { Archive, ChevronRight, CircleAlert, CircleCheck, Info, LoaderCircle, X } from 'lucide-react'
import { nextCursor } from '../shared/api/keys'
import { formatBeijingTime } from '../shared/ui/display'

export function PageHeader({ title, description, actions }: { title: string; description: string; actions?: ReactNode }) {
  return <section className="page-heading"><div><h1>{title}</h1><p>{description}</p></div>{actions && <div className="heading-actions">{actions}</div>}</section>
}

export function Button({ tone = 'secondary', className = '', onClick, ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { tone?: 'primary' | 'secondary' | 'danger' | 'ghost' }) {
  return <button type={props.type ?? 'button'} className={`button ${tone} ${className}`} {...props}
    onClick={(event) => {
      // 部分浏览器点击按钮时不会聚焦，先标记弹窗关闭后应返回的入口。
      if (props['aria-haspopup'] === 'dialog') event.currentTarget.focus({ preventScroll: true })
      onClick?.(event)
    }} />
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
  variant?: 'form' | 'confirm' | 'search'
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

export function DataTable({ label, className = '', ...props }: TableHTMLAttributes<HTMLTableElement> & { label: string }) {
  return (
    <div className="table-scroll" role="region" aria-label={label} tabIndex={0}>
      <table className={`data-table ${className}`} {...props} />
    </div>
  )
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

export function CursorPager({
  after,
  pageSize,
  lastId,
  length,
  onReset,
  onNext,
}: {
  after: number
  pageSize: number
  lastId?: string
  length: number
  onReset: () => void
  onNext: (cursor: number) => void
}) {
  const next = nextCursor(lastId, length, pageSize)
  return <Pager previous={after !== 0} count={length} canAdvance={next !== undefined} onReset={onReset}
    onNext={() => { if (next !== undefined) onNext(next) }} />
}

export function PagePager({
  after,
  nextAfter,
  hasMore,
  observedAt,
  count,
  onReset,
  onNext,
}: {
  after: string
  nextAfter: string | null
  hasMore: boolean
  observedAt?: string
  count?: number
  onReset: () => void
  onNext: (cursor: string) => void
}) {
  return <Pager previous={after !== '0'} count={count} observedAt={observedAt} canAdvance={hasMore && Boolean(nextAfter)}
    onReset={onReset} onNext={() => { if (nextAfter) onNext(nextAfter) }} />
}

function Pager({ previous, count, canAdvance, observedAt, onReset, onNext }: {
  previous: boolean
  count?: number
  canAdvance: boolean
  observedAt?: string
  onReset: () => void
  onNext: () => void
}) {
  return (
    <nav className="pager" aria-label="结果分页">
      <div className="pager-meta">
        {count !== undefined && <span>本页 {count} 条</span>}
        {observedAt && <span>读取于 {formatBeijingTime(observedAt)}（北京时间）</span>}
      </div>
      <div className="pager-actions">
        <Button disabled={!previous} onClick={onReset}>回到首页</Button>
        <Button disabled={!canAdvance} onClick={onNext}>下一页<ChevronRight size={14} aria-hidden="true" /></Button>
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
          <Button tone={tone} onClick={onConfirm} disabled={pending}>{pending ? '正在提交…' : confirmLabel}</Button>
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
