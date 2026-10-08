import { createContext, useContext, type ReactNode } from 'react'
import type { ActiveEffect } from '@/store/fixtureFx'
import type { Fixture } from '@/store/fixtures'

/**
 * Where the sheet is mounted (fixture-fx-sheets plan §3.3). The rows are the same in every host;
 * what differs is the chrome — Focus exists only on the Stage panel, the cards page draws no tab
 * row — and the width, which the sheet answers with container queries, never this.
 */
export type SheetHost = 'popup' | 'stage' | 'phone' | 'card'

/**
 * What every row of one sheet shares, provided once by `FixtureSheet` so a row subscribes only to
 * its own keys. The effect list and the cue / master names are read once here rather than per row.
 */
export interface FixtureSheetContextValue {
  fixture: Fixture
  host: SheetHost
  /** The desk is reachable. Offline the sheet is read-only (D2). */
  connected: boolean
  blind: boolean
  /** The desk's whole active-effect list; rows narrow it with `effectsReaching`. */
  effects: readonly ActiveEffect[]
  cueLabel: (cueId: number) => string | undefined
  effectDetail: (effect: ActiveEffect) => string
  /** The row whose full editor is open (Colour, Position). One at a time — call 5. */
  openRowId: string | null
  setOpenRowId: (id: string | null) => void
  /** The Stage view's *Aim at point* body, for the Position row's *Aim…* popover (D14's half). */
  aim?: ReactNode
}

export const FixtureSheetContext = createContext<FixtureSheetContextValue | null>(null)

export function useFixtureSheet(): FixtureSheetContextValue {
  const value = useContext(FixtureSheetContext)
  if (!value) throw new Error('useFixtureSheet outside a FixtureSheet')
  return value
}
