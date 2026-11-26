import { cloneElement, isValidElement, useEffect, useId, useRef, type ButtonHTMLAttributes, type FormEvent, type ReactElement, type ReactNode } from 'react'
import { X } from 'lucide-react'
import { nextCursor } from '../shared/api/keys'
import { formatBeijingTime } from '../shared/ui/display'

export function PageHeader({ eyebrow, title, description, actions }: { eyebrow: string; title: string; description: string; actions?: ReactNode }) {
  return <section className="page-heading"><div><p className="eyebrow">{eyebrow}</p><h1>{title}</h1><p>{description}</p></div>{actions && <div className="heading-actions">{actions}</div>}</section>
}

export function Button({ tone = 'secondary', className = '', ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { tone?: 'primary' | 'secondary' | 'danger' | 'ghost' }) {
  return <button type={props.type ?? 'button'} className={`button ${tone} ${className}`} {...props} />
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
  return <div className={`state-banner ${tone}`} role={tone === 'error' ? 'alert' : 'status'}><i /><div><strong>{title}</strong><span>{detail}</span></div></div>
}

export function Modal({ title, description, children, onClose }: { title: string; description?: string; children: ReactNode; onClose: () => void }) {
  const card = useRef<HTMLElement>(null)
  useEffect(() => {
    const previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const root = card.current
    const focusable = () => Array.from(root?.querySelectorAll<HTMLElement>('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])') ?? []).filter((node) => !node.hasAttribute('disabled'))
    focusable()[0]?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        onClose()
        return
      }
      if (event.key !== 'Tab') return
      const nodes = focusable()
      if (nodes.length === 0) return
      const first = nodes[0]
      const last = nodes[nodes.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      previouslyFocused?.focus()
    }
  }, [onClose])
  return (
    <div className="modal-layer" role="dialog" aria-modal="true" aria-labelledby="modal-title" onMouseDown={onClose}>
      <section ref={card} className="modal-card" onMouseDown={(event) => event.stopPropagation()}>
        <header>
          <div><h2 id="modal-title">{title}</h2>{description && <p>{description}</p>}</div>
          <button type="button" onClick={onClose} aria-label="关闭"><X size={18} /></button>
        </header>
        {children}
      </section>
    </div>
  )
}

export function EmptyState({ title, detail, action }: { title: string; detail: string; action?: ReactNode }) {
  return <div className="empty-state"><span>◇</span><h3>{title}</h3><p>{detail}</p>{action}</div>
}

export function Field({ label, hint, error, children }: { label: string; hint?: string; error?: string; children: ReactNode }) {
  const id = useId()
  const describedBy = error || hint ? `${id}-desc` : undefined
  const control = isValidElement(children)
    ? cloneElement(children as ReactElement<Record<string, unknown>>, {
        id,
        'aria-invalid': error ? true : undefined,
        'aria-describedby': describedBy,
      })
    : children
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {control}
      {error ? <small id={describedBy}>{error}</small> : hint ? <small id={describedBy} style={{ color: 'var(--ink-400)' }}>{hint}</small> : null}
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
  return (
    <div className="pager">
      <Button disabled={after === 0} onClick={onReset}>回到首页</Button>
      <Button disabled={next === undefined} onClick={() => { if (next !== undefined) onNext(next) }}>下一页</Button>
    </div>
  )
}

export function PagePager({
  after,
  nextAfter,
  hasMore,
  observedAt,
  onReset,
  onNext,
}: {
  after: string
  nextAfter: string | null
  hasMore: boolean
  observedAt?: string
  onReset: () => void
  onNext: (cursor: string) => void
}) {
  return (
    <div className="pager">
      {observedAt ? <small className="pager-meta">读取时间 {formatBeijingTime(observedAt)}（北京时间）</small> : null}
      <Button disabled={after === '0'} onClick={onReset}>回到首页</Button>
      <Button disabled={!hasMore || !nextAfter} onClick={() => { if (nextAfter) onNext(nextAfter) }}>下一页</Button>
    </div>
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
    <Modal title={title} description={description} onClose={onClose}>
      <div className="danger-confirm">
        {children}
        <div className="modal-actions">
          <Button onClick={onClose} disabled={pending}>取消</Button>
          <Button tone={tone} onClick={onConfirm} disabled={pending}>{confirmLabel}</Button>
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
