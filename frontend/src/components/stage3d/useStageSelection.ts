import { useCallback, useMemo, useRef, useState } from 'react'
import type { Selection } from '../stage/stageEditing'

/** A selectable stage object. `Selection` is this or null. */
export type SelectionRef = NonNullable<Selection>

/** Stable key for a ref, for set membership. */
export function selectionKey(ref: SelectionRef): string {
  switch (ref.kind) {
    case 'patch':
      return `patch:${ref.patchKey}`
    case 'region':
      return `region:${ref.uuid}`
    case 'rigging':
      return `rigging:${ref.uuid}`
  }
}

export type SelectIntent = 'replace' | 'toggle' | 'add'

/** Shift or ⌘/Ctrl turns a click into an extend rather than a replace. */
export function selectionIntentFor(
  e: Pick<MouseEvent, 'shiftKey' | 'metaKey' | 'ctrlKey'>,
): SelectIntent {
  if (e.metaKey || e.ctrlKey) return 'toggle'
  if (e.shiftKey) return 'add'
  return 'replace'
}

export interface StageSelection {
  /** The last-clicked object — the anchor. Drives the single-target side panel
   *  and the 3D gizmo, which can only bind to one object. */
  primary: SelectionRef | null
  /** Everything selected, in click order, including `primary`. */
  refs: readonly SelectionRef[]
  selectedKeys: ReadonlySet<string>
  count: number
  isSelected: (ref: SelectionRef) => boolean
  select: (ref: SelectionRef | null, intent?: SelectIntent) => void
  selectMany: (refs: SelectionRef[], intent?: SelectIntent) => void
  clear: () => void
  /** Drops refs whose object no longer exists. */
  reconcile: (exists: (ref: SelectionRef) => boolean) => void
}

/**
 * Multi-object stage selection.
 *
 * Deliberately **does not widen the `Selection` union**. A dozen consumers read
 * that type structurally — `Stage3D`, `resolvePanelTarget`, the three side panels,
 * the picker — and turning it into a set would touch all of them for no gain,
 * since most genuinely want exactly one object. Instead this keeps an ordered list
 * plus an anchor, and hands `primary` to everything that wants a single target.
 *
 * `reconcile` exists because the patch/region/rigging lists are refetched on every
 * WebSocket change, including other operators' edits. Without it, deleting one
 * object elsewhere would either strand a dead ref in the selection or (with the
 * blunter `setSelection(null)`) throw away a multi-selection the user had just
 * built up.
 */
export function useStageSelection(): StageSelection {
  const [refs, setRefs] = useState<SelectionRef[]>([])

  // Read through a ref so the callbacks stay referentially stable — they're
  // dependencies of keyboard-shortcut effects that must not re-bind per render.
  const refsRef = useRef(refs)
  refsRef.current = refs

  const selectedKeys = useMemo(() => new Set(refs.map(selectionKey)), [refs])

  const select = useCallback((ref: SelectionRef | null, intent: SelectIntent = 'replace') => {
    if (ref == null) {
      setRefs([])
      return
    }
    const key = selectionKey(ref)
    setRefs((prev) => {
      const without = prev.filter((r) => selectionKey(r) !== key)
      switch (intent) {
        case 'replace':
          return [ref]
        case 'toggle':
          // Toggling off the only selected object clears; toggling off one of
          // many leaves the rest, with the anchor falling back to the last.
          return without.length === prev.length ? [...without, ref] : without
        case 'add':
          return [...without, ref]
      }
    })
  }, [])

  const selectMany = useCallback((next: SelectionRef[], intent: SelectIntent = 'replace') => {
    setRefs((prev) => {
      if (intent === 'replace') return dedupe(next)
      return dedupe([...prev, ...next])
    })
  }, [])

  const clear = useCallback(() => setRefs([]), [])

  const reconcile = useCallback((exists: (ref: SelectionRef) => boolean) => {
    setRefs((prev) => {
      const kept = prev.filter(exists)
      return kept.length === prev.length ? prev : kept
    })
  }, [])

  const isSelected = useCallback(
    (ref: SelectionRef) => selectedKeys.has(selectionKey(ref)),
    [selectedKeys],
  )

  return {
    // The anchor is the most recent click, which is the end of the list.
    primary: refs.length > 0 ? refs[refs.length - 1] : null,
    refs,
    selectedKeys,
    count: refs.length,
    isSelected,
    select,
    selectMany,
    clear,
    reconcile,
  }
}

function dedupe(refs: SelectionRef[]): SelectionRef[] {
  const seen = new Set<string>()
  const out: SelectionRef[] = []
  for (const ref of refs) {
    const key = selectionKey(ref)
    if (seen.has(key)) continue
    seen.add(key)
    out.push(ref)
  }
  return out
}
