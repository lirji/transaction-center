import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { runtimeConfig } from '../../shared/config/runtime'
import { useAuth } from '../../shared/auth/useAuth'
import './login.css'

export function AuthCallbackPage() {
  const auth = useAuth()
  const navigate = useNavigate()
  const completeLogin = useRef(auth.completeLogin)
  completeLogin.current = auth.completeLogin
  const [error, setError] = useState('')

  useEffect(() => {
    if (runtimeConfig.authMode !== 'OIDC') {
      navigate('/', { replace: true })
      return
    }
    let cancelled = false
    void completeLogin.current().then(
      (returnTo) => { if (!cancelled) navigate(returnTo, { replace: true }) },
      (cause) => { if (!cancelled) setError(cause instanceof Error ? cause.message : 'OIDC 登录失败') },
    )
    return () => { cancelled = true }
  }, [navigate])

  if (error) {
    return (
      <main className="login-root">
        <div className="login-card login-card-narrow">
          <section className="login-form">
              <h1 className="login-form-title">无法完成登录</h1>
            <p className="login-alert" role="alert">{error}</p>
            <Link className="button primary login-submit" to="/login">返回登录</Link>
          </section>
        </div>
      </main>
    )
  }
  return (
    <main className="login-root">
      <p className="login-alert info" role="status">正在完成登录…</p>
    </main>
  )
}
