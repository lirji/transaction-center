import { Activity, ArrowLeftRight, ArrowUpRight, Boxes, Building2, ClipboardList, LayoutDashboard, Menu, SlidersHorizontal } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, Navigate, NavLink, Outlet, Route, Routes, useLocation, useOutletContext, useParams } from 'react-router-dom'
import { OverviewPage } from '../overview/OverviewPage'
import { OrdersPage } from '../orders/OrdersPage'
import { OrderDetailPage } from '../orders/OrderDetailPage'
import { ProductsPage } from '../products/ProductsPage'
import { useAuth } from '../../shared/auth/useAuth'
import { runtimeConfig } from '../../shared/config/runtime'
import { designVariants, previewPath, type DesignVariant } from './previewRoutes'
import './design-preview.css'

const pages = [
  { page: '', label: '经营工作台', icon: LayoutDashboard },
  { page: 'orders', label: '订单管理', icon: ClipboardList },
  { page: 'products', label: '商品管理', icon: Boxes },
] as const

function PreviewShell() {
  const { variant } = useParams()
  const location = useLocation()
  const auth = useAuth()
  const [navOpen, setNavOpen] = useState(false)
  const restoreScroll = location.state?.restoreListScroll === true
  useEffect(() => {
    if (!restoreScroll) window.scrollTo({ top: 0, left: 0 })
  }, [location.pathname, restoreScroll])
  if (!variant || !Object.hasOwn(designVariants, variant)) return <Navigate to={previewPath('modern')} replace />
  const active = variant as DesignVariant
  const suffix = location.pathname.slice(previewPath(active).length + 1)
  const current = pages.find(({ page }) => page === suffix.split('/')[0])
  const marketing = runtimeConfig.workspaces.marketing
  const marketingPreview = marketing && new URL(marketing).hostname === 'localhost'
    ? 'http://localhost:4198/design-preview/campaigns'
    : marketing

  return <div className="design-preview" data-variant={active}>
    <aside id="dp-navigation" className="dp-sidebar" data-open={navOpen}>
      <Link className="dp-brand" to={previewPath(active)} onClick={() => setNavOpen(false)}>
        <span className="dp-mark"><ArrowLeftRight size={19} aria-hidden="true" /></span>
        <span><strong>交易中枢</strong><small>交易运营控制台</small></span>
      </Link>
      <nav aria-label="交易代表页">
        {pages.map(({ page, label, icon: Icon }) => <NavLink key={page} end={!page} to={previewPath(active, page)} onClick={() => setNavOpen(false)}>
          <Icon size={17} aria-hidden="true" />{label}
        </NavLink>)}
        {marketingPreview && <a href={marketingPreview} target="_blank" rel="noreferrer">
          <SlidersHorizontal size={17} aria-hidden="true" />营销活动配置<ArrowUpRight size={13} aria-hidden="true" />
        </a>}
      </nav>
      <div className="dp-sidebar-bottom"><Activity size={16} aria-hidden="true" /> <Link to="/">返回现有控制台</Link></div>
    </aside>
    <div className="dp-main">
      <header className="dp-topbar">
        <div className="dp-location">
          <button className="dp-menu button ghost" type="button" aria-label="切换预览导航" aria-expanded={navOpen}
            aria-controls="dp-navigation" onClick={() => setNavOpen(!navOpen)}><Menu size={19} aria-hidden="true" /></button>
          <span>{current?.label ?? '代表页'}{suffix.startsWith('orders/') ? ' / 订单详情' : ''}</span>
        </div>
        <div className="dp-preview-controls">
          <nav className="dp-variants" aria-label="布局方案">
            {(Object.entries(designVariants) as [DesignVariant, string][]).map(([value, label]) =>
              <Link key={value} aria-current={active === value ? 'page' : undefined}
                to={{ pathname: previewPath(value, suffix), search: location.search }} state={location.state}>{label}</Link>)}
          </nav>
          <span className="dp-tenant"><Building2 size={15} aria-hidden="true" /> 租户 {auth.tenantId || '未声明'}</span>
        </div>
      </header>
      <main className="dp-content"><Outlet context={active} /></main>
    </div>
  </div>
}

function PreviewHome() {
  return <OverviewPage preview={useOutletContext<DesignVariant>()} />
}

function PreviewOrders() {
  return <OrdersPage preview={useOutletContext<DesignVariant>()} />
}

function PreviewDetail() {
  return <OrderDetailPage preview={useOutletContext<DesignVariant>()} />
}

export function DesignPreview() {
  return <Routes>
    <Route path=":variant" element={<PreviewShell />}>
      <Route index element={<PreviewHome />} />
      <Route path="orders" element={<PreviewOrders />} />
      <Route path="orders/:orderNo" element={<PreviewDetail />} />
      <Route path="products" element={<ProductsPage preview />} />
      <Route path="*" element={<Navigate to={previewPath('modern')} replace />} />
    </Route>
    <Route path="*" element={<Navigate to={previewPath('modern')} replace />} />
  </Routes>
}
