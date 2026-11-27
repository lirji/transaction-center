import { Children, cloneElement, isValidElement, type ReactElement, type ReactNode } from 'react'

type Native = ReactElement<{ children?: ReactNode; colSpan?: number; rowSpan?: number; hidden?: boolean }>

function elements(nodes: ReactNode) {
  return Children.toArray(nodes).filter((child): child is Native => isValidElement(child) && typeof child.type === 'string')
}

function caption(children: ReactNode): string {
  if (typeof children === 'string' || typeof children === 'number') return String(children)
  if (Array.isArray(children)) return children.map(caption).join('')
  return isValidElement<{ children?: ReactNode }>(children) ? caption(children.props.children) : ''
}

export function tableColumns(children: ReactNode) {
  const parts = elements(children)
  const header = parts.find((part) => part.type === 'thead')
  const rows = elements(header?.props.children)
  const columns = elements(rows[0]?.props.children).map((cell, index) => ({
    key: `${index}-${caption(cell.props.children)}`,
    label: caption(cell.props.children),
    index,
  }))
  if (rows.length !== 1 || parts.some((part) => part.type === 'colgroup') ||
    parts.flatMap((part) => elements(part.props.children)).some((row) =>
      elements(row.props.children).some((cell) => cell.props.rowSpan || cell.props.colSpan))) return []
  return columns
}

export function applyHiddenColumns(children: ReactNode, hidden: Set<number>) {
  if (!hidden.size) return children
  return Children.map(children, (section) => {
    if (!isValidElement<{ children?: ReactNode }>(section) || !['thead', 'tbody', 'tfoot'].includes(String(section.type))) return section
    return cloneElement(section, undefined, Children.map(section.props.children, (row) => {
      if (!isValidElement<{ children?: ReactNode }>(row) || row.type !== 'tr') return row
      return cloneElement(row, undefined, elements(row.props.children).map((cell, index) =>
        hidden.has(index) ? cloneElement(cell, { hidden: true }) : cell))
    }))
  })
}
