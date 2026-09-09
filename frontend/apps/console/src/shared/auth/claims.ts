export function splitClaim(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.flatMap((item) => splitClaim(item))
  }
  if (typeof value === 'string') {
    return value.split(/[,\s]+/).map((item) => item.trim()).filter(Boolean)
  }
  return []
}

export function decodeJwtPayload(token: string): Record<string, unknown> {
  const parts = token.split('.')
  if (parts.length < 2) return {}
  try {
    const normalized = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = normalized + '='.repeat((4 - (normalized.length % 4)) % 4)
    return JSON.parse(atob(padded)) as Record<string, unknown>
  } catch {
    return {}
  }
}

function firstText(...values: unknown[]): string {
  for (const value of values) {
    if (typeof value === 'string' && value.trim()) return value.trim()
  }
  return ''
}

export function tenantIdFromPayload(payload: Record<string, unknown>): string {
  const nested = payload.properties && typeof payload.properties === 'object' && !Array.isArray(payload.properties)
    ? (payload.properties as Record<string, unknown>).tenant_id
    : undefined
  return firstText(payload.tenant_id, nested)
}

export function scopesFromPayload(payload: Record<string, unknown>): string[] {
  const fromScope = splitClaim(payload.scope)
  const fromPermissions = splitClaim(payload.permissions)
  return Array.from(new Set([...fromScope, ...fromPermissions]))
}

export function audienceFromPayload(payload: Record<string, unknown>): string[] {
  if (Array.isArray(payload.aud)) return payload.aud.map((item) => String(item))
  if (typeof payload.aud === 'string' && payload.aud) return [payload.aud]
  return []
}
