import type { BuskPad } from '@/api/buskApi'
import type { HeldRecord } from '@/api/handApi'
import type { EffectPresence } from './buskingTypes'

/**
 * What a pad does and how it lights, handed down from the view that owns the selection and the
 * queries.
 *
 * One object rather than four props because it is threaded through two layers of pure layout
 * (`BuskPage` → `BuskBankCluster`) that have no opinion about any of it.
 */
export interface PadBehaviour {
  /** A template or Look pad's ring, from the desk's resolved applied state. */
  presenceOf: (pad: BuskPad) => EffectPresence
  /** A cue pad's green: its stack has this cue on stage, playhead or not. */
  isLive: (pad: BuskPad) => boolean
  onPress: (pad: BuskPad) => void
  /** Long press: the pad's hold menu — *Pick up*, and *View* (where the record is edited). */
  onInspect: (pad: BuskPad) => void
  /** The pad's *Pick up*: this record into the desk's hand (multi-screen plan §3.5). */
  onPickUp: (pad: BuskPad) => void
  /**
   * Place what the hand holds as a new pad on this bank, and let go.
   *
   * Threaded here rather than read from `useBuskEdit` for the reason the other four are: the
   * append needs `projectId` and the page id, which the view owns and the two layers of pure
   * layout between it and a bank do not.
   */
  onHandPlace: (bankId: number, bankName: string, held: HeldRecord) => void
}
