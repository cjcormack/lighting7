import { useEffect, useMemo, useReducer } from 'react'
import { lightingApi } from '@/api/lightingApi'
import { effectsReaching, rowSourceOf, type RowSource } from './rowSource'
import { useFixtureSheet } from './sheetContext'

/**
 * A row's source mark (D4): `getKeyState` for each of the row's keys on [headKey], joined with the
 * active-effect list. Subscribes per key (`subscribeToKey`), as the grid's cells do, so a busking
 * burst on one property re-renders that row and no other.
 */
export function useRowSource(headKey: string, keys: readonly string[]): RowSource {
  const { effects, blind, cueLabel, effectDetail, fixture } = useFixtureSheet()
  const [version, bump] = useReducer((n: number) => n + 1, 0)
  const keysKey = keys.join('\u0000')

  useEffect(() => {
    const subs = keysKey.split('\u0000').map((key) => lightingApi.programmer.subscribeToKey(headKey, key, bump))
    return () => subs.forEach((s) => s.unsubscribe())
  }, [headKey, keysKey])

  const reaching = useMemo(
    () => effectsReaching(effects, headKey === fixture.key ? [headKey] : [headKey, fixture.key], fixture.groups),
    [effects, headKey, fixture.key, fixture.groups],
  )

  return useMemo(
    () => {
      void version
      return rowSourceOf({
        headKey,
        states: keysKey.split('\u0000').map((key) => ({ key, state: lightingApi.programmer.getKeyState(headKey, key) })),
        effects: reaching,
        blind,
        cueLabel,
        effectDetail,
      })
    },
    // `version` is the subscription's tick: the states are read from the client's store, which
    // moves without React knowing, and every key's change bumps it.
    [version, headKey, keysKey, reaching, blind, cueLabel, effectDetail],
  )
}
