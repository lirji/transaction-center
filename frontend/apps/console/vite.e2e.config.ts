// 只由E2E命令显式加载；生产构建继续使用vite.config.ts与public/config.js。
import { mergeConfig, type Plugin } from 'vite'
import base from './vite.config'

const port = Number(process.env.TRADE_E2E_UI_PORT ?? 4185)
const integration = process.env.TRADE_E2E_BACKEND_URL
const runtime: Plugin = {
  name: 'isolated-e2e-runtime',
  configureServer(server) {
    server.middlewares.use((req, res, next) => {
      if (req.url?.split('?')[0] !== '/config.js') return next()
      res.setHeader('Content-Type', 'application/javascript')
      res.setHeader('Cache-Control', 'no-store')
      res.end(`globalThis.__TRADE_CONFIG__ = ${JSON.stringify({
        API_BASE_URL: '', AUTH_MODE: integration ? 'OIDC' : 'TEST',
        OIDC_AUTHORITY: process.env.TRADE_E2E_ISSUER,
        OIDC_CLIENT_ID: 'transaction-center', OIDC_AUDIENCE: 'transaction-center',
        OIDC_ORGANIZATION: '', OIDC_SCOPE: 'openid profile',
      })};`)
    })
  },
}
export default mergeConfig(base, {
  plugins: [runtime],
  server: { host: '127.0.0.1', port, strictPort: true,
    proxy: { '/api': { target: integration ?? 'http://127.0.0.1:1', changeOrigin: true } } },
})
