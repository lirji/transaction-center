import type { LucideIcon } from 'lucide-react'
import {
  Boxes,
  Building2,
  ClipboardList,
  CircleGauge,
  CreditCard,
  FileText,
  History,
  Banknote,
  BarChart3,
  Package,
  Receipt,
  RotateCcw,
  ShieldAlert,
  Tag,
  Timer,
  Truck,
  Users,
  Wallet,
  Warehouse,
  Workflow,
} from 'lucide-react'
import { TRADE_ADMIN, TRADE_ADMIN_JOBS_READ } from '../shared/auth/auth-context'

export type NavigationItem = { label: string; path: string; icon: LucideIcon; scopes: string[]; requireAll?: string[] }
export type NavigationGroup = { label: string; items: NavigationItem[] }

export const navigation: NavigationGroup[] = [
  { label: '交易运营', items: [
    { label: '工作台', path: '/', icon: CircleGauge, scopes: [TRADE_ADMIN] },
    { label: '订单', path: '/orders', icon: ClipboardList, scopes: [TRADE_ADMIN] },
    { label: '经营分析', path: '/analytics', icon: BarChart3, scopes: [TRADE_ADMIN] },
  ] },
  { label: '商品与库存', items: [
    { label: '商品', path: '/products', icon: Package, scopes: [TRADE_ADMIN] },
    { label: '仓库与库存', path: '/inventory', icon: Warehouse, scopes: [TRADE_ADMIN] },
    { label: '报价', path: '/quotes', icon: Tag, scopes: [TRADE_ADMIN] },
  ] },
  { label: '资金', items: [
    { label: '支付', path: '/payments', icon: CreditCard, scopes: [TRADE_ADMIN] },
    { label: '实收', path: '/receipts', icon: Banknote, scopes: [TRADE_ADMIN] },
    { label: '对账', path: '/reconciliation', icon: Receipt, scopes: [TRADE_ADMIN] },
    { label: '支付渠道', path: '/payment-channels', icon: Wallet, scopes: [TRADE_ADMIN] },
  ] },
  { label: '履约与售后', items: [
    { label: '履约', path: '/fulfillment', icon: Truck, scopes: [TRADE_ADMIN] },
    { label: '退货', path: '/returns', icon: Boxes, scopes: [TRADE_ADMIN] },
    { label: '退款', path: '/refunds', icon: RotateCcw, scopes: [TRADE_ADMIN] },
  ] },
  { label: '基础资料', items: [
    { label: '商户', path: '/merchant', icon: Building2, scopes: [TRADE_ADMIN] },
    { label: '会员', path: '/members', icon: Users, scopes: [TRADE_ADMIN] },
    { label: '条款', path: '/consents', icon: FileText, scopes: [TRADE_ADMIN] },
  ] },
  { label: '系统管理', items: [
    { label: '准入策略', path: '/admission', icon: ShieldAlert, scopes: [TRADE_ADMIN] },
    { label: 'Outbox', path: '/outbox', icon: Workflow, scopes: [TRADE_ADMIN] },
    { label: '操作日志', path: '/operation-logs', icon: History, scopes: [TRADE_ADMIN] },
    { label: '任务', path: '/jobs', icon: Timer, scopes: [TRADE_ADMIN], requireAll: [TRADE_ADMIN, TRADE_ADMIN_JOBS_READ] },
  ] },
]
