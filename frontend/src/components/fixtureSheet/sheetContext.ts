import { createContext, useContext, type ReactNode } from 'react'
import type { ActiveEffect } from '@/store/fixtureFx'
import type { Fixture } from '@/store/fixtures'
import type { GroupSummary } from '@/api/groupsApi'
import type { GroupSheetMember } from './sheetPick'

/**
 * Where the sheet is mounted (fixture-fx-sheets plan §3.3). The rows are the same in every host;
 * what differs is the chrome — Focus exists only in the Stage view, the cards page draws no tab
 * row — and the width, which the sheet answers with container queries, never this. `phone` is the
 * one host that also changes the rows' sizes: it is the sheet in `useEditorForm`'s two touch forms
 * (`PhoneSheet`), so it is the form — never a viewport width — that says a finger is on it.
 */
export type SheetHost = 'popup' | 'stage' | 'phone' | 'card'

/** The phone host's finger sizes (§4): a 36px field (a setting's box too), … */
export const FINGER_HEIGHT_CLASS = 'h-9'
/**
 * … whose typed text is 16px — below that iOS zooms the page in on a field's focus, and the sheet
 * would slide half off the screen the moment a value was typed, …
 */
export const FINGER_FIELD_CLASS = `${FINGER_HEIGHT_CLASS} text-base`
/** … a 32px × (and the row's editor chevron beside it), … */
export const FINGER_BUTTON_CLASS = 'size-8'
/** … and 32px tray chips. */
export const FINGER_CHIP_CLASS = 'h-8'

/**
 * A row's name line, as tall as its × in either size, so the × appearing when the programmer takes
 * the row never moves the rows below.
 */
export const ROW_HEAD_CLASS = 'min-h-[22px]'
export const FINGER_ROW_HEAD_CLASS = 'min-h-8'

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

/**
 * Whether a control is on the **phone** host and takes finger sizes. Answers `false` outside a sheet
 * rather than throwing: `SheetField` is the live FX editor's field too, which also opens in a
 * popover with no sheet around it.
 */
export function useFingerSized(): boolean {
  return useContext(FixtureSheetContext)?.host === 'phone'
}
