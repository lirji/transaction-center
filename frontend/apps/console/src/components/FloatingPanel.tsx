import { useLayoutEffect, useRef, type ReactNode, type RefObject } from 'react'

export function FloatingPanel({ anchor, children, onClose, width, className = '', id, role, label }: {
  anchor: RefObject<HTMLElement | null>
  children: ReactNode
  onClose: () => void
  width?: number
  className?: string
  id?: string
  role?: string
  label?: string
}) {
  const panel = useRef<HTMLDivElement>(null)
  const supportsPopover = typeof HTMLElement !== 'undefined' && typeof HTMLElement.prototype.showPopover === 'function'
  const close = useRef(onClose)
  useLayoutEffect(() => { close.current = onClose }, [onClose])

  useLayoutEffect(() => {
    const element = panel.current
    const trigger = anchor.current
    if (!element || !trigger) return
    element.showPopover?.()
    const position = () => {
      const rect = trigger.getBoundingClientRect()
      const available = window.innerWidth - 16
      const panelWidth = Math.min(width ?? Math.max(rect.width, 156), available)
      element.style.width = `${panelWidth}px`
      element.style.left = `${Math.max(8, Math.min(rect.left, window.innerWidth - panelWidth - 8))}px`
      const below = window.innerHeight - rect.bottom - 12
      const above = rect.top - 12
      const up = element.scrollHeight > below && above > below
      element.style.maxHeight = `${Math.max(60, Math.min(up ? above : below, 360))}px`
      element.style.top = up ? 'auto' : `${rect.bottom + 5}px`
      element.style.bottom = up ? `${window.innerHeight - rect.top + 5}px` : 'auto'
    }
    position()
    element.querySelector<HTMLElement>('[data-popup-focus]')?.focus({ preventScroll: true })
    const dismiss = (event: PointerEvent) => {
      const target = event.target
      if (target instanceof Node && !element.contains(target) && !trigger.contains(target)) close.current()
    }
    window.addEventListener('scroll', position, true)
    window.addEventListener('resize', position)
    document.addEventListener('pointerdown', dismiss)
    return () => {
      window.removeEventListener('scroll', position, true)
      window.removeEventListener('resize', position)
      document.removeEventListener('pointerdown', dismiss)
      element.hidePopover?.()
    }
  }, [anchor, width])

  return (
    <div ref={panel} id={id} className={`floating-panel ${className}`} popover={supportsPopover ? 'manual' : undefined} role={role} aria-label={label}>
      {children}
    </div>
  )
}
