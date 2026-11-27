import { useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { Check, ChevronDown, Search } from 'lucide-react'
import { FloatingPanel } from './FloatingPanel'

export type SelectOption = { value: string; label: string; disabled?: boolean }

type SelectProps = {
  value: string
  onChange: (value: string) => void
  options?: readonly SelectOption[]
  children?: ReactNode
  id?: string
  required?: boolean
  disabled?: boolean
  searchable?: boolean
  className?: string
  placeholder?: string
  'aria-label'?: string
  'aria-invalid'?: boolean
  'aria-describedby'?: string
}

/** 复用原有 option 表达式，把可见控件和键盘行为集中到一个组件。 */
function readOptions(children: ReactNode): SelectOption[] {
  if (Array.isArray(children)) return children.flatMap(readOptions)
  if (!children || typeof children !== 'object' || !('type' in children) || children.type !== 'option') return []
  const { value, children: label, disabled } = children.props as { value?: string; children?: ReactNode; disabled?: boolean }
  const text = Array.isArray(label) ? label.join('') : String(label ?? value ?? '')
  return [{ value: value ?? text, label: text, disabled }]
}

export function Select({
  value, onChange, options: providedOptions, children, id, required, disabled, searchable,
  className = '', placeholder = '请选择', ...aria
}: SelectProps) {
  const options = providedOptions ?? readOptions(children)
  const [open, setOpen] = useState(false)
  const [filter, setFilter] = useState('')
  const [active, setActive] = useState(0)
  const trigger = useRef<HTMLButtonElement>(null)
  const listId = useId()
  const selected = options.find((option) => option.value === value)
  const search = searchable ?? options.length > 9
  const filtered = options.filter((option) => option.label.toLocaleLowerCase().includes(filter.trim().toLocaleLowerCase()))
  const usable = filtered.map((option, index) => option.disabled ? -1 : index).filter((index) => index >= 0)
  const current = filtered[active]

  function close(returnFocus = false) {
    setOpen(false)
    if (returnFocus) trigger.current?.focus({ preventScroll: true })
  }

  function show() {
    setFilter('')
    const index = options.findIndex((option) => option.value === value && !option.disabled)
    setActive(index < 0 ? options.findIndex((option) => !option.disabled) : index)
    setOpen(true)
  }

  function choose(option: SelectOption | undefined) {
    if (!option || option.disabled) return
    onChange(option.value)
    close(true)
  }

  function handleKeys(event: KeyboardEvent<HTMLElement>) {
    if (event.key === 'Escape' && open) {
      event.preventDefault()
      event.stopPropagation()
      close(true)
      return
    }
    if (event.key === 'Tab') {
      close()
      return
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp' || (open && (event.key === 'Home' || event.key === 'End'))) {
      event.preventDefault()
      if (!open) {
        show()
        return
      }
      const index = usable.indexOf(active)
      const next = event.key === 'Home' ? usable[0]
        : event.key === 'End' ? usable.at(-1)
        : usable[Math.max(0, Math.min(index + (event.key === 'ArrowDown' ? 1 : -1), usable.length - 1))]
      if (next !== undefined) {
        setActive(next)
        document.getElementById(`${listId}-${next}`)?.scrollIntoView?.({ block: 'nearest' })
      }
      return
    }
    if (open && (event.key === 'Enter' || (event.key === ' ' && event.target === trigger.current))) {
      event.preventDefault()
      choose(current)
    }
  }

  return (
    <div className={`select-control ${className}`}>
      {required && <input className="select-validity" aria-hidden="true" tabIndex={-1} disabled={disabled} required
        value={value} onChange={() => undefined} onInvalid={(event) => {
          event.preventDefault()
          trigger.current?.focus()
          show()
        }} />}
      <button ref={trigger} id={id} type="button" role="combobox" className="select-trigger" disabled={disabled}
        aria-expanded={open} aria-controls={open ? listId : undefined} aria-required={required || undefined}
        aria-activedescendant={open && current ? `${listId}-${active}` : undefined}
        onClick={() => open ? close() : show()} onKeyDown={handleKeys} {...aria}>
        <span className="select-value">{selected?.label ?? (value || placeholder)}</span>
        <ChevronDown size={15} aria-hidden="true" />
      </button>
      {open && (
        <FloatingPanel anchor={trigger} className="select-popup" onClose={() => close()}>
          {search && <div className="select-search"><Search size={14} aria-hidden="true" />
            <input data-popup-focus aria-label="搜索选项" aria-controls={listId} value={filter}
              onChange={(event) => {
                const term = event.target.value
                setFilter(term)
                setActive(options.filter((option) => option.label.toLocaleLowerCase().includes(term.trim().toLocaleLowerCase()))
                  .findIndex((option) => !option.disabled))
              }} onKeyDown={handleKeys} /></div>}
          <div role="listbox" id={listId} aria-label="可选项">
            {filtered.map((option, index) => (
              <button type="button" role="option" tabIndex={-1} className={`select-option ${active === index ? 'option-active' : ''}`}
                key={option.value} id={`${listId}-${index}`} aria-selected={value === option.value} disabled={option.disabled}
                onPointerMove={() => { if (!option.disabled) setActive(index) }}
                onMouseDown={(event) => event.preventDefault()} onClick={() => choose(option)}>
                <span>{option.label}</span>{value === option.value && <Check size={15} aria-hidden="true" />}
              </button>
            ))}
            {filtered.length === 0 && <p className="select-no-result">没有匹配选项</p>}
          </div>
        </FloatingPanel>
      )}
    </div>
  )
}
