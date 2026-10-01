import { useMemo } from 'react'
import { indexLanterns, type LanternIndex } from '../lib/lanterns'
import { useLanternListQuery } from '../store/fixtures'

/** The lantern library (`GET /lanterns`), indexed by id and by each kind's default. */
export function useLanternIndex(): LanternIndex {
  const { data } = useLanternListQuery()
  return useMemo(() => indexLanterns(data), [data])
}
