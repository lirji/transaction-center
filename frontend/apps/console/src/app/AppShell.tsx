import { Suspense, useEffect, useMemo, useRef, useState } from 'react'
import { ArrowLeftRight, Building2, ChevronDown, ChevronRight, Command, Menu, Search, X } from 'lucide-react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { navigation } from './navigation'
import { useAuth } from '../shared/auth/useAuth'
import { ErrorBoundary } from '../components/ErrorBoundary'
import { Button, Modal } from '../components/ui'

export function AppShell() {
  const [navOpen, setNavOpen] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [logoutOpen, setLogoutOpen] = useState(false)
  const [query, setQuery] = useState('')
  const openMenu = useRef<HTMLButtonElement>(null)
  const closeMenu = useRef<HTMLButtonElement>(null)
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
  const segments = location.pathname.split('/').filter(Boolean)
  const sectionPath = segments.length ? `/${segments[0]}` : '/'
  const group = visibleNav.find(({ items }) => items.some(({ path }) => path === sectionPath))
  const current = group?.items.find(({ path }) => path === sectionPath)
  const isDetail = Boolean(current && segments.length > 1)
  const listSearch = location.state?.ordersSearch ?? location.state?.listSearch
  const results = visibleNav
    .flatMap((entry) => entry.items.map((item) => ({ ...item, group: entry.label })))
    .filter((item) => `${item.label} ${item.group}`.toLowerCase().includes(query.trim().toLowerCase()))
  const audienceOk = auth.audience.includes('transaction-center') || auth.audience.length === 0
  const restoreOrder = Boolean(location.state?.returnedOrder)

  useEffect(() => {
    setNavOpen(false)
    if (!restoreOrder && (window.scrollY || window.scrollX)) window.scrollTo({ top: 0, left: 0 })
  }, [location.pathname, restoreOrder])
  useEffect(() => {
    if (!navOpen) return
    closeMenu.current?.focus()
    const onEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setNavOpen(false)
    }
    const desktop = window.matchMedia('(min-width: 801px)')
    const onResize = () => { if (desktop.matches) setNavOpen(false) }
    window.addEventListener('keydown', onEscape)
    desktop.addEventListener('change', onResize)
    return () => {
      window.removeEventListener('keydown', onEscape)
      desktop.removeEventListener('change', onResize)
      requestAnimationFrame(() => openMenu.current?.focus({ preventScroll: true }))
    }
  }, [navOpen])

  useEffect(() => {
    const handleKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k' && !document.querySelector('dialog[open]')) {
        event.preventDefault()
        setQuery('')
        setNavOpen(false)
        setSearchOpen(true)
      }
    }
    window.addEventListener('keydown', handleKey)
    return () => window.removeEventListener('keydown', handleKey)
  }, [])

  return (
    <div className="app-shell">
      {!navOpen && <a className="button primary skip-link" href="#main-content">跳至内容</a>}
      <aside className={`sidebar ${navOpen ? 'open' : ''}`} id="console-navigation">
        <div className="brand">
          <span className="brand-mark"><ArrowLeftRight size={18} aria-hidden="true" /></span>
          <span><strong>交易中枢</strong><small>运营控制台</small></span>
          <button className="icon-button mobile-close" ref={closeMenu} type="button" onClick={() => setNavOpen(false)} aria-label="关闭菜单"><X size={18} /></button>
        </div>
        <nav aria-label="主导航">
          {visibleNav.map((entry) => (
            <div className="nav-group" key={entry.label}>
              <p className="nav-label">{entry.label}</p>
              {entry.items.map(({ label, path, icon: Icon }) => (
                <NavLink end={path === '/'} className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`} to={path} key={path} onClick={() => setNavOpen(false)}>
                  <Icon size={16} aria-hidden="true" /><span>{label}</span>
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
        <div className="sidebar-context"><Building2 size={15} aria-hidden="true" />租户 {auth.tenantId || '未声明'}</div>
      </aside>
      {navOpen && <button className="nav-backdrop" type="button" aria-label="关闭导航遮罩" tabIndex={-1} onClick={() => setNavOpen(false)} />}
      <div className="shell-main" inert={navOpen}>
        <header className="topbar">
          <button className="icon-button mobile-menu" ref={openMenu} type="button" aria-label="打开菜单" aria-expanded={navOpen}
            aria-controls="console-navigation" onClick={() => setNavOpen(true)}><Menu size={19} /></button>
          <nav className="breadcrumbs" aria-label="当前位置">
            <ol>
              {group && sectionPath !== '/' && <li className="breadcrumb-group">{group.label}<ChevronRight size={14} aria-hidden="true" /></li>}
              {isDetail ? (
                <>
                  <li><Link to={{ pathname: sectionPath, search: typeof listSearch === 'string' ? listSearch : '' }}
                    state={sectionPath === '/orders' ? { returnedOrder: location.state?.listOrderRef } : undefined}>{current?.label}</Link><ChevronRight size={14} aria-hidden="true" /></li>
                  <li aria-current="page">详情</li>
                </>
              ) : <li aria-current="page">{current?.label ?? '控制台'}</li>}
            </ol>
          </nav>
          <div className="tenant-context" title={audienceOk ? '当前登录租户' : '身份应用与交易中枢不匹配'}>
            <Building2 size={15} aria-hidden="true" />租户 {auth.tenantId || '未声明'}{audienceOk ? '' : ' · 应用不匹配'}
          </div>
          <button className="global-search" aria-label="搜索模块" aria-haspopup="dialog" type="button" onClick={(event) => {
            event.currentTarget.focus({ preventScroll: true })
            setQuery('')
            setSearchOpen(true)
          }}>
            <Search size={16} aria-hidden="true" /><span>搜索模块</span><kbd title="Ctrl 或 Command + K"><Command size={11} aria-hidden="true" /> K</kbd>
          </button>
          <button className="profile" type="button" aria-haspopup="dialog" onClick={(event) => {
            event.currentTarget.focus({ preventScroll: true })
            setLogoutOpen(true)
          }} aria-label={`账户 ${auth.displayName}`} title={auth.subject}>
            <span aria-hidden="true">{auth.displayName.slice(0, 1)}</span>
            <strong>{auth.displayName}</strong>
            <ChevronDown size={14} aria-hidden="true" />
          </button>
        </header>
        <main id="main-content" tabIndex={-1}>
          <ErrorBoundary>
            <Suspense fallback={<div className="route-loading" role="status">正在加载页面…</div>}>
              <Outlet />
            </Suspense>
          </ErrorBoundary>
        </main>
      </div>
      {searchOpen && (
        <Modal variant="search" title="搜索模块" onClose={() => setSearchOpen(false)}>
          <div className="command-input">
            <Search size={18} aria-hidden="true" />
            <input data-initial-focus aria-label="模块名称" value={query} onChange={(event) => setQuery(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.nativeEvent.isComposing && results.length === 1) {
                  navigate(results[0].path)
                  setSearchOpen(false)
                }
              }} placeholder="输入模块名称" />
          </div>
          <div className="command-results">
            {results.map(({ label, path, group: groupLabel, icon: Icon }) => (
              <button key={path} type="button" onClick={() => { navigate(path); setSearchOpen(false) }}>
                <Icon size={16} aria-hidden="true" /><span>{label}</span><small>{groupLabel}</small>
              </button>
            ))}
            {results.length === 0 && <p role="status">没有匹配的模块</p>}
          </div>
        </Modal>
      )}
      {logoutOpen && (
        <Modal variant="confirm" title="退出登录" description="将结束当前运营会话并返回身份提供方。" onClose={() => setLogoutOpen(false)}>
          <div className="danger-confirm">
            <p>确认退出「{auth.displayName}」吗？</p>
            <footer className="modal-actions">
              <Button onClick={() => setLogoutOpen(false)} data-initial-focus>取消</Button>
              <Button tone="danger" onClick={() => void auth.logout()}>退出</Button>
            </footer>
          </div>
        </Modal>
      )}
    </div>
  )
}
