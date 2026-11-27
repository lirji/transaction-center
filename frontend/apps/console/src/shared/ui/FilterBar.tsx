import { useId, useState, type FormEvent, type ReactNode, type SelectHTMLAttributes } from 'react'
import { ChevronDown, Search } from 'lucide-react'
import { Button, Field } from '../../components/ui'

export function FilterBar({ children, onSubmit, onReset, advanced, activeAdvanced = 0, invalid = false }: {
  children: ReactNode
  onSubmit: () => void
  onReset?: () => void
  advanced?: ReactNode
  activeAdvanced?: number
  invalid?: boolean
}) {
  const id = useId()
  const [expanded, setExpanded] = useState(false)
  const showAdvanced = expanded || invalid
  return (
    <form className="filter-bar" aria-label="查询条件" onSubmit={(event: FormEvent) => {
      event.preventDefault()
      onSubmit()
    }}>
      <div className="filter-fields">{children}</div>
      <div className="filter-actions">
        <Button tone="primary" type="submit"><Search size={14} aria-hidden="true" />查询</Button>
        {onReset && <Button onClick={onReset}>清空筛选</Button>}
        {advanced && <Button tone="ghost" aria-expanded={showAdvanced} aria-controls={id} disabled={invalid}
          onClick={() => setExpanded(!showAdvanced)}>
          高级筛选{activeAdvanced > 0 && <span className="filter-count" aria-label={`已应用 ${activeAdvanced} 项高级条件`}>{activeAdvanced}</span>}
          <ChevronDown size={15} aria-hidden="true" />
        </Button>}
      </div>
      {advanced && <div className="filter-fields advanced-fields" id={id} hidden={!showAdvanced}>{advanced}</div>}
    </form>
  )
}

export function TimeRangeFields({
  from,
  to,
  onFrom,
  onTo,
  error,
  labelPrefix = '',
}: {
  from: string
  to: string
  onFrom: (value: string) => void
  onTo: (value: string) => void
  error?: string
  labelPrefix?: string
}) {
  return (
    <div className="time-range-fields">
      <Field label={`${labelPrefix}起始时间（北京时间）`} hint="起止时间成对填写，最长 90 天">
        <input type="datetime-local" step="0.001" value={from} onChange={(event) => onFrom(event.target.value)} />
      </Field>
      <Field label={`${labelPrefix}结束时间（北京时间）`} hint="不包含结束时刻" error={error}>
        <input type="datetime-local" step="0.001" value={to} onChange={(event) => onTo(event.target.value)} />
      </Field>
    </div>
  )
}

export function EmptySelect({
  value,
  onChange,
  options,
  allowEmpty = true,
  optionLabel,
  id,
  ...rest
}: {
  value: string
  onChange: (value: string) => void
  options: readonly string[]
  allowEmpty?: boolean
  optionLabel?: (value: string) => string
} & Omit<SelectHTMLAttributes<HTMLSelectElement>, 'onChange' | 'value'>) {
  return (
    <select id={id} value={value} onChange={(event) => onChange(event.target.value)} {...rest}>
      {allowEmpty ? <option value="">全部</option> : null}
      {options.map((item) => <option key={item} value={item}>{optionLabel?.(item) ?? item}</option>)}
    </select>
  )
}
