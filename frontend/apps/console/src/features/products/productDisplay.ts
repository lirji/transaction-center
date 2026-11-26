import type { productStatusSchema, stockTypeSchema } from '../../shared/api/schemas'

export const productStatusLabels: Record<(typeof productStatusSchema.options)[number], string> = {
  DRAFT: '草稿', PUBLISHED: '已发布', OFFLINE: '已下架',
}

export const stockTypeLabels: Record<(typeof stockTypeSchema.options)[number], string> = {
  PHYSICAL: '实物', SERVICE: '服务',
}
