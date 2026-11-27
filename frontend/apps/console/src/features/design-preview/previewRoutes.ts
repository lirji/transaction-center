export const designVariants = {
  modern: '现代简约',
  operations: '高密度运营',
  analytics: '数据驱动',
} as const

export type DesignVariant = keyof typeof designVariants

export function previewPath(variant: DesignVariant, page = '') {
  return `/design-preview/${variant}${page ? `/${page}` : ''}`
}
