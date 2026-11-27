import { useEffect } from 'react'
import { ArrowLeftRight, ArrowRight, KeyRound, LoaderCircle, LogIn } from 'lucide-react'
import { Navigate, useSearchParams } from 'react-router-dom'
import { oidcOrigin, runtimeConfig } from '../../shared/config/runtime'
import { useAuth } from '../../shared/auth/useAuth'
import { sanitizeReturnTo } from '../../shared/auth/tenantSelection'
import './login.css'

export function LoginPage() {
  const auth = useAuth()
  const [params] = useSearchParams()
  const returnTo = sanitizeReturnTo(params.get('returnTo'))
  const clientReady = Boolean(runtimeConfig.oidcClientId)
  const canSubmit = auth.ready && !auth.redirecting && clientReady

  useEffect(() => {
    const previous = document.title
    document.title = '登录 · 交易中枢'
    return () => { document.title = previous }
  }, [])

  if (auth.ready && auth.authenticated) return <Navigate to={returnTo} replace />

  return (
    <main className="login-root">
      <div className="login-card">
        <aside className="login-aside" aria-label="交易中枢">
          <div className="login-brand">
            <span className="brand-mark"><ArrowLeftRight size={18} aria-hidden="true" /></span>
            <strong>交易中枢</strong>
          </div>
          <div>
            <h2 className="login-aside-title">交易运营控制台</h2>
            <p>查询交易记录，处理退款和履约，维护商品、库存与商户。</p>
          </div>
        </aside>
        <section className="login-form">
          <h1 className="login-form-title">登录交易中枢</h1>
          <p className="login-form-sub">使用机构账户访问获授权的租户与业务模块。</p>
          {!auth.ready ? <p className="login-alert info" role="status">正在检查本地会话…</p> : null}
          {!clientReady ? <p className="login-alert" role="alert">未配置 OIDC 客户端，无法跳转 Casdoor。请检查控制台的身份应用配置。</p> : null}
          {auth.error ? <p className="login-alert" role="alert">{auth.error}</p> : null}
          <button
            className="login-submit"
            type="button"
            disabled={!canSubmit}
            aria-busy={auth.redirecting || undefined}
            onClick={() => void auth.login(returnTo)}
          >
            {auth.redirecting ? <LoaderCircle size={16} aria-hidden="true" /> : <LogIn size={16} aria-hidden="true" />}
            {auth.redirecting ? '正在跳转 Casdoor…' : '使用 Casdoor 登录'}
            {!auth.redirecting ? <ArrowRight size={16} aria-hidden="true" /> : null}
          </button>
          <p className="login-idp">
            <KeyRound size={14} aria-hidden="true" />
            <span>身份提供方 {oidcOrigin || runtimeConfig.oidcAuthority || '未配置'}</span>
          </p>
          <details className="login-help">
            <summary>登录与权限帮助</summary>
            <p>若登录后没有模块访问权限，请联系管理员检查登录应用、租户和 <code>trade.admin</code> 权限。</p>
          </details>
        </section>
      </div>
    </main>
  )
}
