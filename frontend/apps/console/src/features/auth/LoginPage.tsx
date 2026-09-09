import { useEffect } from 'react'
import { ArrowRight, KeyRound, LoaderCircle, LogIn, ShieldCheck, Store, Wallet } from 'lucide-react'
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
        <aside className="login-aside">
          <div className="login-brand">
            <span className="login-brand-mark" aria-hidden="true">T</span>
            <span>
              <strong>交易中枢</strong>
              <small>TRANSACTION</small>
            </span>
          </div>
          <div>
            <p className="login-aside-kicker">内部运营台</p>
            <h1 className="login-aside-title">后台可审计<br />资金不口头承诺</h1>
            <p className="login-aside-sub">这不是会员商城。金额按最小单位整数提交；写入带幂等键与版本条件。本台不会自行拼接 JWT。</p>
            <ul className="login-features">
              <li>
                <span className="login-feature-icon"><ShieldCheck size={16} aria-hidden="true" /></span>
                <span><b>统一身份</b><small>Casdoor 签发 aud=transaction-center，scope 含 trade.admin</small></span>
              </li>
              <li>
                <span className="login-feature-icon"><Store size={16} aria-hidden="true" /></span>
                <span><b>租户来自令牌</b><small>tenant_id 必须是正整数文本，不能用请求头覆盖</small></span>
              </li>
              <li>
                <span className="login-feature-icon"><Wallet size={16} aria-hidden="true" /></span>
                <span><b>不发明接口</b><small>OpenAPI 未交付的能力不会出现可操作按钮</small></span>
              </li>
            </ul>
          </div>
        </aside>
        <section className="login-form">
          <p className="login-kicker">Casdoor SSO · PKCE</p>
          <h2 className="login-form-title">登录交易中枢</h2>
          <p className="login-form-sub">使用已配置的身份应用进入运营台。登录后令牌须同时满足：</p>
          <ul className="login-reqs">
            <li><code>aud</code> 为 transaction-center</li>
            <li><code>scope</code> 含 trade.admin</li>
            <li>演示数据需要 <code>tenant_id=900001</code></li>
          </ul>
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
            {auth.redirecting ? <LoaderCircle className="login-spin" size={16} aria-hidden="true" /> : <LogIn size={16} aria-hidden="true" />}
            {auth.redirecting ? '正在跳转 Casdoor…' : '使用 Casdoor 登录'}
            {!auth.redirecting ? <ArrowRight size={16} aria-hidden="true" /> : null}
          </button>
          <p className="login-idp">
            <KeyRound size={14} aria-hidden="true" />
            <span>身份提供方 {oidcOrigin || runtimeConfig.oidcAuthority || '未配置'}</span>
          </p>
          <p className="login-secure-note">若登录后 403，说明令牌缺少 trade.admin 或 tenant_id，需要在 IAM 补齐，而不是在页面里改请求头。</p>
        </section>
      </div>
      <p className="login-foot">内部运营使用 · API {runtimeConfig.apiBaseUrl || '/api → 127.0.0.1:8400'}</p>
    </main>
  )
}
