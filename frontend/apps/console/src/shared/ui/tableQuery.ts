import { createContext } from 'react'

export const TableQueryContext = createContext<{ refresh: () => void; refreshing: boolean } | undefined>(undefined)
