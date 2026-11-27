import { useId, useRef, useState, type InputHTMLAttributes } from 'react'
import { CalendarDays, ChevronLeft, ChevronRight } from 'lucide-react'
import { formatBeijingInput } from '../shared/api/businessTime'
import { FloatingPanel } from './FloatingPanel'

const weekdayNames = ['一', '二', '三', '四', '五', '六', '日']

export function DateTimePicker({ value, onChange, ...props }: {
  value: string
  onChange: (value: string) => void
} & Omit<InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange' | 'type'>) {
  const anchor = useRef<HTMLDivElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const popupId = useId()
  const [month, setMonth] = useState<string>()
  const today = formatBeijingInput(Date.now()).slice(0, 10)

  function close(returnFocus = false) {
    setMonth(undefined)
    if (returnFocus) trigger.current?.focus({ preventScroll: true })
  }

  function pick(date: string) {
    const existingTime = value.split('T')[1]
    onChange(`${date}T${existingTime && /^\d{2}:\d{2}/.test(existingTime) ? existingTime : '00:00'}`)
    close(true)
  }

  function shift(direction: number) {
    if (!month) return
    const date = new Date(`${month}-01T00:00:00Z`)
    date.setUTCMonth(date.getUTCMonth() + direction)
    setMonth(date.toISOString().slice(0, 7))
  }

  const first = month ? new Date(`${month}-01T00:00:00Z`) : undefined
  const offset = first ? (first.getUTCDay() + 6) % 7 : 0
  const last = first ? new Date(first) : undefined
  if (last) last.setUTCMonth(last.getUTCMonth() + 1, 0)
  const days = last?.getUTCDate() ?? 0
  const activeDate = value.slice(0, 10)
  const selectedDay = activeDate.startsWith(month ?? '-') && Number(activeDate.slice(8)) <= days
    ? Number(activeDate.slice(8)) : 1

  return (
    <div className="datetime-control" ref={anchor}>
      <input type="text" placeholder="年-月-日 时:分" autoComplete="off" {...props}
        value={value.replace('T', ' ')} onChange={(event) => onChange(event.target.value.replace(' ', 'T'))} />
      <button ref={trigger} type="button" className="date-calendar-button" aria-label="打开日历"
        aria-expanded={Boolean(month)} aria-controls={month ? popupId : undefined} disabled={props.disabled}
        onClick={() => {
          if (month) close()
          else setMonth(/^\d{4}-\d{2}-\d{2}/.test(value) && Number.isFinite(Date.parse(value.slice(0, 10)))
            ? value.slice(0, 7) : today.slice(0, 7))
        }}><CalendarDays size={16} aria-hidden="true" /></button>
      {month && (
        <FloatingPanel id={popupId} anchor={anchor} width={294} className="date-calendar" role="group" label="选择日期"
          onClose={() => close()}>
          <div onKeyDown={(event) => {
            if (event.key === 'Escape') {
              event.preventDefault()
              event.stopPropagation()
              close(true)
            }
            if (event.key === 'Tab' && !event.shiftKey && event.target === event.currentTarget.lastElementChild) close()
          }}>
            <div className="calendar-navigation">
              <button type="button" className="icon-button" aria-label="上个月" onClick={() => shift(-1)}><ChevronLeft size={16} /></button>
              <strong aria-live="polite">{month.replace('-', ' 年 ')} 月</strong>
              <button type="button" className="icon-button" aria-label="下个月" onClick={() => shift(1)}><ChevronRight size={16} /></button>
            </div>
            <div className="calendar-days">
              {weekdayNames.map((weekday) => <span key={weekday}>{weekday}</span>)}
              {Array.from({ length: offset }, (_, index) => <span key={`offset-${index}`} />)}
              {Array.from({ length: days }, (_, index) => {
                const date = `${month}-${String(index + 1).padStart(2, '0')}`
                const selected = value.startsWith(date)
                return <button type="button" key={date} aria-label={date} aria-pressed={selected}
                  data-popup-focus={index + 1 === selectedDay ? '' : undefined}
                  className={date === today ? 'calendar-today' : undefined} onClick={() => pick(date)}>{index + 1}</button>
              })}
            </div>
            <button type="button" className="calendar-pick-today" onClick={() => pick(today)}>今天</button>
          </div>
        </FloatingPanel>
      )}
    </div>
  )
}
