import { useEffect, useMemo, useState } from 'react'
import { ChevronDown, Command, Menu, Search, X } from 'lucide-react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { navigation } from './navigation'
import { useAuth } from '../shared/auth/useAuth'
import { ErrorBoundary } from '../components/ErrorBoundary'
import { Modal } from '../components/ui'
import { TRADE_ADMIN } from '../shared/auth/auth-context'

export function AppShell() {
  const [navOpen, setNavOpen] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [logoutOpen, setLogoutOpen] = useState(false)
  const [query, setQuery] = useState('')
  const navigate = useNavigate()
  const location = useLocation()
  const auth = useAuth()
  const visibleNav = useMemo(
    () => navigation
      .map((group) => ({
        ...group,
        items: group.items.filter((item) =>
          auth.hasAnyScope(item.scopes) && (!item.requireAll || item.requireAll.every((scope) => auth.hasScope(scope))),
        ),
      }))
      .filter((group) => group.items.length > 0),
    [auth],
  )
  const searchable = useMemo(() => visibleNav.flatMap((group) => group.items), [visibleNav])
  const results = searchable.filter((item) => item.label.toLowerCase().includes(query.toLowerCase()))
  const tenantOk = /^[1-9][0-9]*$/.test(auth.tenantId)
  const audienceOk = auth.audience.includes('transaction-center') || auth.audience.length === 0

  useEffect(() => setNavOpen(false), [location.pathname])
  useEffect(() => {
    const handleKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        setSearchOpen(true)
      }
      if (event.key === 'Escape') setSearchOpen(false)
    }
    window.addEventListener('keydown', handleKey)
    return () => window.removeEventListener('keydown', handleKey)
  }, [])

  return (
    <div className="app-shell">
      <aside className={`sidebar ${navOpen ? 'open' : ''}`}>
        <div className="brand"><span className="brand-mark">T</span><span><strong>交易中枢</strong><small>TRANSACTION</small></span><button className="mobile-close" type="button" onClick={() => setNavOpen(false)} aria-label="关闭菜单"><X size={17} /></button></div>
        <nav aria-label="主导航">
          {visibleNav.map((group) => (
            <div className="nav-group" key={group.label}>
              <p className="nav-label">{group.label}</p>
              {group.items.map(({ label, path, icon: Icon }) => (
                <NavLink end={path === '/'} className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`} to={path} key={path}>
                  <Icon size={17} aria-hidden="true" /><span>{label}</span>
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
        <div className="sidebar-foot">
          <div className={`health-orb ${auth.hasScope(TRADE_ADMIN) && tenantOk ? 'ok' : 'unknown'}`} />
          <div>
            <strong>{auth.tenantId || '未声明租户'}</strong>
            <small>{auth.hasScope(TRADE_ADMIN) ? 'trade.admin' : '缺少 trade.admin'}</small>
          </div>
        </div>
      </aside>
      {navOpen && <button className="nav-backdrop" type="button" aria-label="关闭菜单" onClick={() => setNavOpen(false)} />}
      <main>
        <header className="topbar">
          <button className="mobile-menu" type="button" aria-label="打开菜单" onClick={() => setNavOpen(true)}><Menu size={19} /></button>
          <div className="tenant-switch" title="租户来自 JWT tenant_id，不能用请求头覆盖。">
            租户 {auth.tenantId || '未声明'}{audienceOk ? '' : ' · aud 未匹配 transaction-center'}
          </div>
          <button className="global-search" type="button" onClick={() => setSearchOpen(true)}><Search size={16} /><span>搜索模块…</span><kbd><Command size={11} /> K</kbd></button>
          <button className="profile" type="button" onClick={() => setLogoutOpen(true)} title="账户">
            <span>{auth.displayName.slice(0, 1)}</span>
            <div><strong>{auth.displayName}</strong><small>{auth.subject || '未登录'}</small></div>
            <ChevronDown size={14} />
          </button>
        </header>
        <ErrorBoundary>
          <Outlet />
        </ErrorBoundary>
      </main>
      {searchOpen && (
        <div className="modal-layer" role="dialog" aria-modal="true" aria-labelledby="command-title" onMouseDown={() => setSearchOpen(false)}>
          <section className="command-panel" onMouseDown={(event) => event.stopPropagation()}>
            <div className="command-input"><Search size={18} /><h2 id="command-title" className="sr-only">全局搜索</h2><input autoFocus value={query} onChange={(event) => setQuery(event.target.value)} placeholder="输入模块名称…" /><kbd>ESC</kbd></div>
            <div className="command-results">{results.map(({ label, path, icon: Icon }) => <button key={path} type="button" onClick={() => { navigate(path); setSearchOpen(false) }}><Icon size={16} /><span>{label}</span><small>{path}</small></button>)}</div>
          </section>
        </div>
      )}
      {logoutOpen && (
        <Modal title="退出登录" description="将结束当前运营会话并返回身份提供方。" onClose={() => setLogoutOpen(false)}>
          <div className="danger-confirm">
            <p>确认退出「{auth.displayName}」吗？</p>
            <footer className="modal-actions">
              <button className="button secondary" type="button" onClick={() => setLogoutOpen(false)}>取消</button>
              <button className="button danger" type="button" onClick={() => void auth.logout()}>退出</button>
            </footer>
          </div>
        </Modal>
      )}
    </div>
  )
}
