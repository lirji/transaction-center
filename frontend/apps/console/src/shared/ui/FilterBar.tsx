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
}: {
  from: string
  to: string
  onFrom: (value: string) => void
  onTo: (value: string) => void
  error?: string
}) {
  return (
    <>
      <Field label="createdFrom" hint="须与结束时间成对；UTC [from,to)，最大 90 天">
        <input type="datetime-local" value={from} onChange={(event) => onFrom(event.target.value)} />
      </Field>
      <Field label="createdTo" error={error}>
        <input type="datetime-local" value={to} onChange={(event) => onTo(event.target.value)} />
      </Field>
    </>
  )
}

export function EmptySelect({
  value,
  onChange,
  options,
  allowEmpty = true,
  id,
  ...rest
}: {
  value: string
  onChange: (value: string) => void
  options: readonly string[]
  allowEmpty?: boolean
} & Omit<SelectHTMLAttributes<HTMLSelectElement>, 'onChange' | 'value'>) {
  const items = useMemo(() => options, [options])
  return (
    <select id={id} value={value} onChange={(event) => onChange(event.target.value)} {...rest}>
      {allowEmpty ? <option value="">全部</option> : null}
      {items.map((item) => <option key={item} value={item}>{item}</option>)}
    </select>
  )
}
