import { createContext, useContext } from 'react'
import type { CueAdHocFx, CueLayerPressResponse, CueTarget } from '@/api/cuesApi'
import type { FxPresence } from '@/components/busking/buskingTypes'
import type { AttributeFamily } from '@/lib/attributeFamily'

/**
 * The cue editor's arm of the press (apply-model plan §3.6, session 8): a press from a door inside
 * the editor — a value cell's bands, the Looks tab's picker — writes the **stored** cue and sends
 * nothing to the stage. `CueRowStoreProvider` provides it, so every door under the editor is in it
 * and none outside is; `usePress` reads it before the programmer's scope.
 *
 * The desk owns the rule (plan P2): [pressLayer] sends the press and answers what the desk wrote.
 */
export interface PressCue {
  projectId: number
  cueId: number
  /** `Q1`, or the cue's name — where a press goes, as the bands say it. */
  cueLabel: string
  /** Locked, or another project's cue. */
  readOnly: boolean
  /** The records the cue layers, keyed `LOOK:id` / `PALETTE:id` — a chip's ring ([cuePresence]). */
  layered: ReadonlySet<string>
  /** The cue's own effects, as the editor draws them. */
  effects: readonly CueAdHocFx[]
  /**
   * A palette or a Look pressed into the cue's layers, in the editor's save chain: what it had
   * pending lands first, and the press is one Undo. A palette's press carries the heads and the mask;
   * a Look's carries neither, since it lands whole. Null when the desk refused it, which
   * `errorToastMiddleware` has already said.
   */
  pressLayer: (
    source:
      | { paletteId: number; targets: readonly CueTarget[]; families: readonly AttributeFamily[] | null }
      | { lookId: number },
  ) => Promise<CueLayerPressResponse | null>
  /** *Apply as values*: the desk's literals as the cue's own rows. Answers how many landed. */
  landValues: (values: readonly { target: CueTarget; attribute: string; value: string }[]) => number
  /**
   * A bare effect stored in draft (show-cue-editing plan D17), nothing run: appended, or in place
   * of [replacing] — the effect the picker is auditioning, found in the cue as the desk holds it.
   * Resolves false when nothing was written (read-only, or the save failed).
   */
  pickFx: (effect: CueAdHocFx, replacing: CueAdHocFx | null) => Promise<boolean>
}

export const PressCueContext = createContext<PressCue | null>(null)

/** The cue a press from here lands in, or null outside the cue editor. */
export function usePressCue(): PressCue | null {
  return useContext(PressCueContext)
}

/**
 * The project a cell's bands press into in the cue editor, which passes the grid none: absent
 * outside the editor and while the cue is read-only, so a locked cue's cells draw their editors bare.
 */
export function useCueBandProject(): number | undefined {
  const cue = usePressCue()
  return cue != null && !cue.readOnly ? cue.projectId : undefined
}

/**
 * A chip's ring in the cue arm: lit (partly) when the cue layers the record anywhere. A cue layer
 * covering every pressed head is not told apart — the desk owns that coverage rule, and the press's
 * answer says which arm it took.
 */
export function cuePresence(cue: PressCue, kind: 'LOOK' | 'PALETTE', id: number): FxPresence {
  return cue.layered.has(`${kind}:${id}`) ? 'some' : 'none'
}
