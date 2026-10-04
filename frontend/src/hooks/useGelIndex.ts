import { useMemo } from 'react'
import { indexGels, type GelIndex } from '../lib/gels'
import { useGelListQuery } from '../store/fixtures'

/** The gel library (`GET /gels`), indexed by code. Empty while it loads. */
export function useGelIndex(): GelIndex {
  const { data } = useGelListQuery()
  return useMemo(() => indexGels(data), [data])
}
