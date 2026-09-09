import type { QueryClient } from '@tanstack/react-query'
import { isVersionConflict } from '../api/client'

export async function invalidateIfVersionConflict(queryClient: QueryClient, error: unknown, queryKey: unknown[]) {
  if (isVersionConflict(error)) {
    await queryClient.invalidateQueries({ queryKey })
  }
}
