import { Component, type ReactNode } from 'react'

type Props = { children: ReactNode }
type State = { message?: string }

export class ErrorBoundary extends Component<Props, State> {
  state: State = {}

  static getDerivedStateFromError(error: Error): State {
    return { message: error.message || '工作台发生未处理错误' }
  }

  render() {
    if (!this.state.message) return this.props.children
    return (
      <div className="auth-error" role="alert">
        <h1>工作台暂时不可用</h1>
        <p>请刷新页面。若持续失败，请保留当前路径并联系平台值班。</p>
        <p>{this.state.message}</p>
        <button className="button primary" type="button" onClick={() => window.location.reload()}>刷新工作台</button>
      </div>
    )
  }
}
