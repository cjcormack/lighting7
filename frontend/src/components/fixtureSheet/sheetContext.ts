import { createContext, useContext, type ReactNode } from 'react'
import type { ActiveEffect } from '@/store/fixtureFx'
import type { Fixture } from '@/store/fixtures'
import type { GroupSummary } from '@/api/groupsApi'
import type { GroupSheetMember } from './sheetPick'

/**
 * Where the sheet is mounted (fixture-fx-sheets plan §3.3). The rows are the same in every host;
 * what differs is the chrome — Focus exists only on the Stage panel, the cards page draws no tab
 * row — and the width, which the sheet answers with container queries, never this.
 */
export type SheetHost = 'popup' | 'stage' | 'phone' | 'card'

/**
 * What one sheet is about (D1): a fixture, or a group with the members it draws pips for — the
 * fixtures (or single heads) the group names that are patched and visible, in the group's order.
 */
export type SheetTarget =
  | { type: 'fixture'; fixture: Fixture }
  | { type: 'group'; group: GroupSummary; members: readonly GroupSheetMember[] }

/** The sheet's target as the programmer and the stack address it. */
export function sheetTargetKey(target: SheetTarget): string {
  return target.type === 'fixture' ? target.fixture.key : target.group.name
}

/**
 * What every row of one sheet shares, provided once by `FixtureSheet` so a row subscribes only to
 * its own keys. The effect list and the cue / master names are read once here rather than per row.
 */
export interface FixtureSheetContextValue {
  target: SheetTarget
  host: SheetHost
  /** The desk is reachable. Offline the sheet is read-only (D2). */
  connected: boolean
  blind: boolean
  /** The desk's whole active-effect list; rows narrow it with `effectsReaching`. */
  effects: readonly ActiveEffect[]
  /**
   * The targets whose effects can paint one head — `effectsReaching`'s two arguments: the head, its
   * fixture when it is a head of one (the desk paints a fixture's effect onto its heads), and the
   * groups that fixture is in.
   */
  reachOf: (headKey: string) => { keys: readonly string[]; groups: readonly string[] }
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
