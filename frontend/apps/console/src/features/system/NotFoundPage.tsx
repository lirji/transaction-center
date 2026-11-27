import { Link } from 'react-router-dom'
import { EmptyState, PageHeader, Panel } from '../../components/ui'

export function NotFoundPage() {
  return <div className="workspace record-workspace">
    <PageHeader title="页面没有找到" description="请检查链接地址，或从主导航重新打开业务页面。" />
    <Panel><EmptyState title="这个链接已失效" detail="可返回工作台查看待办和常用入口。"
      action={<Link className="button primary" to="/">返回工作台</Link>} /></Panel>
  </div>
}
