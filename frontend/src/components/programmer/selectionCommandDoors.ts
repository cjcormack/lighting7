import type { SubselectMode } from '@/api/selectionApi'
import type { RigVerbs } from '@/components/fixtures-list/RigVerbs'
import { FAMILY_LABELS, PALETTE_FAMILY_ORDER, formatFamilyList, type AttributeFamily } from '@/lib/attributeFamily'
import { SUBSELECT_FILTER_MODES, SUBSELECT_MODE_LABELS, SUBSELECT_STEP_MODES } from '@/lib/cellsSubSelection'
import { relinkToDesk } from '@/lib/deskFollow'
import { unlinkFromDeskNow } from '@/store/selection'
import { countOf, useCommandDoor, type ProgrammerCommand } from './commandDoors'

/** What row C knows about the selection, and its handlers — the container's, handed to ⌘K. */
export interface SelectionCommandsInput {
  /** The heads the next press lands on. */
  heads: number
  /** The marquee's cells, 0 for rows alone. */
  cells: number
  /** The mask the next press carries, null for every attribute. */
  families: readonly AttributeFamily[] | null
  /** Set opens on the cells: the scope writes, and there are cells. */
  canSet: boolean
  onSet: () => void
  canFan: boolean
  onFan: () => void
  /** ⌫ releases the cells: the scope clears, and there are cells. */
  canReleaseCells: boolean
  onReleaseCells: () => void
  /** The heads Release's whole-heads arm takes — a group row is every member. */
  releaseHeads: number
  canReleaseHeads: boolean
  onReleaseHeads: () => void
  onSubselect: (mode: SubselectMode) => void
  /** The mask's write while the window follows the desk; null when the mask is read-only. */
  onRemask: ((families: readonly AttributeFamily[] | null) => void) | null
  rigVerbs: RigVerbs
  following: boolean
  onDeselect: () => void
}

/** The step modes' ⌘K words, which read as a move rather than a filter. */
const STEP_LABELS: Partial<Record<SubselectMode, string>> = {
  PREV: 'Previous',
  NEXT: 'Next',
}

/**
 * ⌘K's *Selection* group (programmer-toolbar plan D13), registered by the programmer's list while
 * it is mounted: Set, Fan, the two Releases, the subsets, the mask, the rig verbs, the follow toggle
 * and Deselect, each running what row C's control runs. *Apply a palette…* and *Add FX on the
 * selection…* are their own doors (`ProgrammerPaletteDoor`, `ProgrammerAddFxDoor`).
 */
export function useSelectionCommandDoors(input: SelectionCommandsInput | null): void {
  const enabled = input != null
  const selected = (i: SelectionCommandsInput) => i.heads > 0 || i.cells > 0
  const on = (i: SelectionCommandsInput) =>
    i.families != null && i.families.length > 0 ? `${countOf(i.heads, 'head')} · ${formatFamilyList(i.families, ' · ')}` : countOf(i.heads, 'head')

  useCommandDoor(
    'set',
    () =>
      input == null
        ? []
        : [
            {
              id: 'selection.set',
              group: 'selection',
              label: 'Set the selected cells',
              hint: countOf(input.cells, 'cell'),
              keywords: ['value', 'edit', 'enter'],
              kbd: '⏎',
              canAct: input.canSet,
              run: input.onSet,
            },
          ],
    enabled,
  )

  useCommandDoor(
    'fan',
    () =>
      input == null
        ? []
        : [
            {
              id: 'selection.fan',
              group: 'selection',
              label: 'Fan across the selection…',
              hint: on(input),
              keywords: ['spread', 'fan', 'between', 'about'],
              canAct: input.canFan,
              run: input.onFan,
            },
          ],
    enabled,
  )

  useCommandDoor(
    'release',
    () =>
      input == null
        ? []
        : [
            {
              id: 'selection.releaseCells',
              group: 'selection',
              label: 'Release the selected cells',
              hint: countOf(input.cells, 'cell'),
              keywords: ['clear', 'remove', 'local'],
              kbd: '⌫',
              canAct: input.canReleaseCells,
              run: input.onReleaseCells,
            },
            {
              id: 'selection.releaseHeads',
              group: 'selection',
              label: 'Release the whole heads',
              hint: `${countOf(input.releaseHeads, 'head')} · values and Local FX`,
              keywords: ['clear', 'remove', 'local', 'fx'],
              canAct: input.canReleaseHeads,
              run: input.onReleaseHeads,
            },
          ],
    enabled,
  )

  useCommandDoor(
    'subsets',
    () =>
      input == null
        ? []
        : [...SUBSELECT_FILTER_MODES, ...SUBSELECT_STEP_MODES].map<ProgrammerCommand>((mode) => ({
            id: `selection.subset.${mode}`,
            group: 'selection',
            label: STEP_LABELS[mode] ?? SUBSELECT_MODE_LABELS[mode],
            hint: `In rig order · ${countOf(input.heads, 'head')}`,
            keywords: ['rig order', 'narrow', 'subset', 'select', 'elements'],
            canAct: selected(input),
            run: () => input.onSubselect(mode),
          })),
    enabled,
  )

  useCommandDoor(
    'mask',
    () => {
      if (input == null) return []
      const current = input.families != null && input.families.length > 0 ? input.families : null
      const now = `now ${current == null ? 'every attribute' : formatFamilyList(current, ' · ')}`
      const choices: { label: string; families: AttributeFamily[] | null }[] = [
        { label: 'All', families: null },
        ...PALETTE_FAMILY_ORDER.map((family) => ({ label: FAMILY_LABELS[family].singular, families: [family] })),
      ]
      return choices.map<ProgrammerCommand>((choice) => ({
        id: `selection.mask.${choice.families?.[0] ?? 'ALL'}`,
        group: 'selection',
        label: `Mask: ${choice.label}`,
        hint: now,
        keywords: ['mask', 'attribute', 'family'],
        // Read-only unlinked: the window's own selection has no mask but the marquee's columns.
        canAct: selected(input) && input.onRemask != null && !sameMask(current, choice.families),
        run: () => input.onRemask?.(choice.families),
      }))
    },
    enabled,
  )

  useCommandDoor(
    'rig',
    () => {
      if (input == null) return []
      const verbs = input.rigVerbs
      const heads = countOf(input.heads, 'head')
      return [
        {
          id: 'selection.locate',
          group: 'selection',
          label: verbs.allLocated ? 'Release locate' : 'Locate the selection',
          hint: heads,
          keywords: ['locate', 'home', 'white'],
          canAct: selected(input),
          run: verbs.locate,
        },
        {
          id: 'selection.storeLocate',
          group: 'selection',
          label: 'Set locate from current',
          hint: `${heads} · stored on the patch`,
          keywords: ['locate', 'store', 'position'],
          canAct: selected(input) && verbs.canStoreLocate && !verbs.storedLocate.busy,
          run: () => verbs.storedLocate.storeFromCurrent(verbs.locateTargets),
        },
        {
          // A command cannot be held, so here Highlight latches, as the phone's menu does.
          id: 'selection.highlight',
          group: 'selection',
          label: verbs.highlight.isActive ? 'Stop highlighting' : 'Highlight the selection',
          hint: heads,
          keywords: ['highlight', 'find', 'identify'],
          canAct: selected(input),
          run: verbs.highlight.isActive ? verbs.highlight.release : verbs.highlight.press,
        },
      ]
    },
    enabled,
  )

  useCommandDoor(
    'follow',
    () =>
      input == null
        ? []
        : [
            input.following
              ? {
                  id: 'selection.follow',
                  group: 'selection',
                  label: 'This window’s own selection',
                  hint: 'stop following the desk',
                  keywords: ['unlink', 'follow', 'desk', 'own'],
                  canAct: true,
                  run: unlinkFromDeskNow,
                }
              : {
                  id: 'selection.follow',
                  group: 'selection',
                  label: 'Follow the desk selection',
                  hint: 'takes the desk’s selection',
                  keywords: ['link', 'follow', 'desk'],
                  canAct: true,
                  run: relinkToDesk,
                },
          ],
    enabled,
  )

  useCommandDoor(
    'deselect',
    () =>
      input == null
        ? []
        : [
            {
              id: 'selection.deselect',
              group: 'selection',
              label: 'Deselect',
              hint: input.cells > 0 ? countOf(input.cells, 'cell') : countOf(input.heads, 'head'),
              keywords: ['clear selection', 'none', 'escape'],
              canAct: selected(input),
              run: input.onDeselect,
            },
          ],
    enabled,
  )
}

function sameMask(a: readonly AttributeFamily[] | null, b: readonly AttributeFamily[] | null): boolean {
  if (a == null || b == null) return a == null && b == null
  return a.length === b.length && a.every((f) => b.includes(f))
}
