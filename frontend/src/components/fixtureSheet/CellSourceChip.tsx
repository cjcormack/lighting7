import { useEffect, useMemo, useReducer } from 'react'
import { lightingApi } from '@/api/lightingApi'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { useProgrammerBlind } from '@/hooks/useProgrammerBlind'
import { useActiveEffectsQuery } from '@/store/fixtureFx'
import { useIsDeskConnected } from '@/store/status'
import type { Fixture } from '@/store/fixtures'
import { useCueLabel, useEffectDetail } from './effectLabels'
import { effectsReaching, mergeRowSources, rowSourceOf } from './rowSource'
import { SourceChip } from './SourceChip'

/** A cell's `(head, property)` keys — the grid's `RowCell.keys`. */
export interface CellKey {
  targetKey: string
  propertyName: string
}

/**
 * The programmer grid's **source chip** (fixture-fx-sheets plan D19): the fixture sheet's
 * `SourceChip` for one cell, on the cell editor's label line — the popover's right side, a row under
 * the title in both sheet forms (`EditorLabelLine`'s `chip`). It names what drives the cell's
 * value over the heads it covers — *Programmer · 2 of 4* where they differ — carries the amber dot
 * while the programmer holds an effect back under it, and opens the property's stack
 * (`LayerStack`, W1) beside it.
 *
 * Mounted inside the editor's content, so it subscribes to its keys only while an editor is open;
 * the rule is the sheet's (`rowSourceOf` per head, `mergeRowSources` over them), with what the
 * sheet's context would carry read here.
 */
export function CellSourceChip({ keys, label }: { keys: readonly CellKey[]; label: string }) {
  const { data: effects } = useActiveEffectsQuery()
  const { fixtures } = useFixtureLookup()
  const blind = useProgrammerBlind()
  const connected = useIsDeskConnected()
  const cueLabel = useCueLabel()
  const effectDetail = useEffectDetail()
  const [version, bump] = useReducer((n: number) => n + 1, 0)

  const signature = keys.map((k) => `${k.targetKey}\u0001${k.propertyName}`).join('\u0000')
  useEffect(() => {
    const subs = signature
      .split('\u0000')
      .filter(Boolean)
      .map((pair) => {
        const [head, property] = pair.split('\u0001')
        return lightingApi.programmer.subscribeToKey(head, property, bump)
      })
    return () => subs.forEach((s) => s.unsubscribe())
  }, [signature])

  /** Each head's own properties, in the cell's order. */
  const heads = useMemo(() => {
    const byHead = new Map<string, string[]>()
    for (const k of keys) {
      const list = byHead.get(k.targetKey)
      if (list) {
        if (!list.includes(k.propertyName)) list.push(k.propertyName)
      } else byHead.set(k.targetKey, [k.propertyName])
    }
    return [...byHead.entries()]
    // `signature` is `keys` spelled as text, so a fresh array of the same keys does not rebuild.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signature])

  const lookup = useMemo(() => headLookup(fixtures ?? []), [fixtures])

  const source = useMemo(() => {
    void version
    if (heads.length === 0) return null
    return mergeRowSources(
      heads.map(([head, properties]) => {
        const reach = lookup.reachOf(head)
        return {
          key: head,
          source: rowSourceOf({
            headKey: head,
            states: properties.map((key) => ({ key, state: lightingApi.programmer.getKeyState(head, key) })),
            effects: effectsReaching(effects, reach.keys, reach.groups),
            blind,
            cueLabel,
            effectDetail,
          }),
        }
      }),
    )
    // `version` is the subscription's tick: the states are read from the client's store.
  }, [version, heads, lookup, effects, blind, cueLabel, effectDetail])

  if (source == null) return null
  return (
    <SourceChip
      source={source}
      heads={heads.map(([key]) => ({ key, name: lookup.nameOf(key) }))}
      propertyName={heads[0][1][0]}
      label={label}
      cueLabel={cueLabel}
      connected={connected}
    />
  )
}

/** A head's name and what reaches it, by key — a fixture, or one head of one. */
function headLookup(fixtures: readonly Fixture[]) {
  const byKey = new Map<string, { name: string; reach: { keys: string[]; groups: readonly string[] } }>()
  for (const fixture of fixtures) {
    byKey.set(fixture.key, { name: fixture.name, reach: { keys: [fixture.key], groups: fixture.groups } })
    for (const element of fixture.elements ?? []) {
      byKey.set(element.key, {
        name: `${fixture.name} ${element.displayName}`,
        reach: { keys: [element.key, fixture.key], groups: fixture.groups },
      })
    }
  }
  return {
    nameOf: (key: string) => byKey.get(key)?.name ?? key,
    reachOf: (key: string) => byKey.get(key)?.reach ?? { keys: [key], groups: [] },
  }
}
