import { ArrowDown, ArrowLeft } from 'lucide-react'
import { Link, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { PageHeader } from './ui'

export function RecordHeader({ title, description, listPath, listLabel, actions }: {
  title: string
  description: string
  listPath: string
  listLabel: string
  actions?: ReactNode
}) {
  const { state } = useLocation()
  const search = typeof state?.listSearch === 'string' ? state.listSearch : undefined
  return <PageHeader title={title} description={description} actions={<>
    <Link className="button secondary" to={{ pathname: listPath, search }}
      state={search !== undefined ? { restoreListScroll: true } : undefined}>
      <ArrowLeft size={15} aria-hidden="true" />{listLabel}
    </Link>
    {actions}
  </>} />
}

export function WorkSections({ sections }: { sections: ReadonlyArray<{ id: string; label: string }> }) {
  return <nav className="work-sections" aria-label="本页分区">
    {sections.map(({ id, label }) => <a key={id} href={`#${id}`}>{label}<ArrowDown size={13} aria-hidden="true" /></a>)}
  </nav>
}
