import { useCallback, useMemo, useState } from 'react'
import type { CueTarget } from '@/api/cuesApi'
import { selectionKeyOf } from './draftFx'
import type { AuditionedFx } from './FxPicker'

const NONE: ReadonlyMap<string, AuditionedFx> = new Map()

/**
 * The FX a picker session has started, one for each selection it started over. `FxPicker`'s
 * [current] is the one started over [targets]: once the selection moves, a tap starts afresh on the
 * new heads and leaves the old FX running on the old ones; back on those heads, it is the FX a tap
 * swaps again. [onCurrent] files under the selection of the render it was handed out in, which is
 * the selection the tap started over, however long the start took.
 */
export function useAuditionedFx(targets: readonly CueTarget[]): {
  current: AuditionedFx | null
  onCurrent: (next: AuditionedFx | null) => void
  /** Every FX the session started and still holds, on whichever selection. */
  started: readonly AuditionedFx[]
  /** Drop one stopped by hand. */
  forget: (fxId: number) => void
  reset: () => void
} {
  const over = selectionKeyOf(targets)
  const [byOver, setByOver] = useState(NONE)
  const onCurrent = useCallback(
    (next: AuditionedFx | null) =>
      setByOver((prev) => {
        const map = new Map(prev)
        if (next == null) map.delete(over)
        else map.set(over, next)
        return map
      }),
    [over],
  )
  const forget = useCallback(
    (fxId: number) => setByOver((prev) => new Map([...prev].filter(([, fx]) => fx.fxId !== fxId))),
    [],
  )
  const reset = useCallback(() => setByOver(NONE), [])
  const started = useMemo(() => [...byOver.values()], [byOver])
  return { current: byOver.get(over) ?? null, onCurrent, started, forget, reset }
}
