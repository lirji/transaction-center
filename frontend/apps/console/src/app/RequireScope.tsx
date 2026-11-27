import type { ReactNode } from 'react'
import { useAuth } from '../shared/auth/useAuth'
import { EmptyState, PageHeader, Panel } from '../components/ui'

export function RequireScope({ anyOf, children }: { anyOf: string[]; children: ReactNode }) {
  const auth = useAuth()
  if (auth.hasAnyScope(anyOf)) return children
  return (
    <div className="workspace record-workspace">
      <PageHeader title="没有访问该模块的权限" description="请联系管理员核对当前账号和租户的权限。" />
      <Panel><EmptyState title="所需访问权限" detail={anyOf.join('、')} /></Panel>
    </div>
  )
}
