import { useEffect, useMemo, useReducer } from 'react'
import { lightingApi } from '@/api/lightingApi'
import { effectsReaching, mergeRowSources, rowSourceOf, type PickSource, type RowSource } from './rowSource'
import { useFixtureSheet } from './sheetContext'

/**
 * A row's source mark (D4): `getKeyState` for each of the row's keys on [headKey], joined with the
 * active-effect list. Subscribes per key (`subscribeToKey`), as the grid's cells do, so a busking
 * burst on one property re-renders that row and no other.
 */
export function useRowSource(headKey: string, keys: readonly string[]): RowSource {
  return usePickSource([headKey], keys).perHead[0].source
}

/**
 * A row's source over a pick of heads (D13): each head's own `rowSourceOf`, merged — the strongest
 * source on the chip with how many of the heads it holds (*2 of 4*), the edge dashed where the heads
 * disagree. One subscription per (head, key), so a row over twelve pixels hears its twelve keys.
 */
export function usePickSource(headKeys: readonly string[], keys: readonly string[]): PickSource {
  const { effects, blind, cueLabel, effectDetail, reachOf } = useFixtureSheet()
  const [version, bump] = useReducer((n: number) => n + 1, 0)
  const headsKey = headKeys.join('\u0000')
  const keysKey = keys.join('\u0000')

  useEffect(() => {
    const subs = headsKey
      .split('\u0000')
      .flatMap((head) => keysKey.split('\u0000').map((key) => lightingApi.programmer.subscribeToKey(head, key, bump)))
    return () => subs.forEach((s) => s.unsubscribe())
  }, [headsKey, keysKey])

  const reaching = useMemo(
    () =>
      headsKey.split('\u0000').map((head) => {
        const reach = reachOf(head)
        return effectsReaching(effects, reach.keys, reach.groups)
      }),
    [effects, headsKey, reachOf],
  )

  return useMemo(
    () => {
      void version
      const heads = headsKey.split('\u0000')
      return mergeRowSources(
        heads.map((head, i) => ({
          key: head,
          source: rowSourceOf({
            headKey: head,
            states: keysKey.split('\u0000').map((key) => ({ key, state: lightingApi.programmer.getKeyState(head, key) })),
            effects: reaching[i],
            blind,
            cueLabel,
            effectDetail,
          }),
        })),
      )
    },
    // `version` is the subscription's tick: the states are read from the client's store, which
    // moves without React knowing, and every key's change bumps it.
    [version, headsKey, keysKey, reaching, blind, cueLabel, effectDetail],
  )
}
