import { useCallback, useState, type ReactNode } from 'react'
import type { CueTarget } from '@/api/cuesApi'
import { ReplaceStack, useLocalFxHolder } from './ReplaceStack'

const NO_TARGETS: readonly CueTarget[] = []

/**
 * The question for one picker session over [targets], where [asks]: wire [onLanding] and [stackOn]
 * into `FxPicker` and draw [segment]. It is asked of every **fresh start** — `FxPicker` reports only
 * the attributes a tap would start afresh on, and [current], the session's own FX, is never "already
 * here", since a fresh start removes it first. A swap is not asked: it keeps how its FX started
 * (`AuditionedFx.stacked`). Each start puts the question back on *Replace*, as does [reset].
 */
export function useReplaceStack(
  targets: readonly CueTarget[],
  asks: boolean,
  current: { fxId: number } | null,
): {
  segment: ReactNode
  onLanding: (attributes: readonly string[]) => void
  stackOn: (attribute: string) => boolean
  reset: () => void
} {
  const holderOf = useLocalFxHolder(asks ? targets : NO_TARGETS)
  const [landing, setLanding] = useState<readonly string[]>([])
  const [stack, setStack] = useState(false)
  const except = current?.fxId ?? null
  const [startedId, setStartedId] = useState(except)
  if (startedId !== except) {
    setStartedId(except)
    setStack(false)
  }
  const holder = asks ? (landing.map((attribute) => holderOf(attribute, except)).find((h) => h != null) ?? null) : null
  const stackOn = useCallback((attribute: string) => stack && holderOf(attribute, except) != null, [stack, holderOf, except])
  const reset = useCallback(() => setStack(false), [])
  return {
    segment: holder != null ? <ReplaceStack stack={stack} onChange={setStack} holder={holder} /> : null,
    onLanding: setLanding,
    stackOn,
    reset,
  }
}
