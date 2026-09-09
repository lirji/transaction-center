import type { User, UserManager } from 'oidc-client-ts'
import { sanitizeReturnTo } from './tenantSelection'

let inFlight: Promise<User> | undefined

export function returnToFromState(state: unknown) {
  if (typeof state === 'object' && state !== null && 'returnTo' in state) {
    return sanitizeReturnTo((state as { returnTo: unknown }).returnTo)
  }
  return '/'
}

export function completeOidcRedirect(manager: UserManager): Promise<User> {
  inFlight ??= manager.signinRedirectCallback()
  return inFlight
}
