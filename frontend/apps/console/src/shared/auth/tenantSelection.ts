export function sanitizeReturnTo(value: unknown, fallback = '/'): string {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) return fallback
  if (value.startsWith('/auth/callback') || value.startsWith('/login')) return fallback
  return value
}
