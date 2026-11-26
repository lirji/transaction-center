import { useMemo, type FormEvent, type ReactNode, type SelectHTMLAttributes } from 'react'
import { Button, Field } from '../../components/ui'

export function FilterBar({ children, onSubmit }: { children: ReactNode; onSubmit: () => void }) {
  return (
    <form
      className="filter-bar"
      onSubmit={(event: FormEvent) => {
        event.preventDefault()
        onSubmit()
      }}
    >
      {children}
      <div className="modal-actions">
        <Button tone="primary" type="submit">查询</Button>
      </div>
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
  const items = useMemo(() => options, [options])
  return (
    <select id={id} value={value} onChange={(event) => onChange(event.target.value)} {...rest}>
      {allowEmpty ? <option value="">全部</option> : null}
      {items.map((item) => <option key={item} value={item}>{optionLabel?.(item) ?? item}</option>)}
    </select>
  )
}
