import { useId, useState, type FormEvent, type ReactNode } from 'react'
import { ChevronDown, Search, X } from 'lucide-react'
import { Button, Field } from '../../components/ui'
import { Select } from '../../components/Select'
import { DateTimePicker } from '../../components/DateTimePicker'
import type { AppliedFilter } from './appliedFilters'
import { beijingRange, businessRangeOptions } from '../api/businessTime'

export function FilterBar({ children, onSubmit, onReset, advanced, activeAdvanced = 0, invalid = false, applied = [] }: {
  children: ReactNode
  onSubmit: () => void
  onReset?: () => void
  advanced?: ReactNode
  activeAdvanced?: number
  invalid?: boolean
  applied?: readonly AppliedFilter[]
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
      {applied.length > 0 && <div className="applied-filters" role="group" aria-label="已生效筛选">
        <span className="applied-filter-label">已生效</span>
        {applied.map(({ id: key, label, value, clear }) => <Button tone="ghost" className="applied-filter"
          key={key} onClick={clear} aria-label={`取消筛选 ${label}`} title={`${label} ${value}`}>
          <span>{label} · {value}</span><X size={13} aria-hidden="true" />
        </Button>)}
      </div>}
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
        <DateTimePicker value={from} onChange={onFrom} />
      </Field>
      <Field label={`${labelPrefix}结束时间（北京时间）`} hint="不包含结束时刻" error={error}>
        <DateTimePicker value={to} onChange={onTo} />
      </Field>
      <div className="range-shortcuts" role="group" aria-label={`${labelPrefix}快捷时间`}>
        {businessRangeOptions.map(([range, label]) => {
          const values = beijingRange(range)
          return <Button key={range} tone="ghost" aria-pressed={from === values.from && to === values.to} onClick={() => {
            onFrom(values.from)
            onTo(values.to)
          }}>{label}</Button>
        })}
      </div>
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
} & Pick<Parameters<typeof Select>[0], 'id' | 'disabled' | 'required' | 'aria-invalid' | 'aria-describedby'>) {
  return (
    <Select id={id} value={value} onChange={onChange} {...rest}>
      {allowEmpty ? <option value="">全部</option> : null}
      {options.map((item) => <option key={item} value={item}>{optionLabel?.(item) ?? item}</option>)}
    </Select>
  )
}
