import { useCallback, useEffect, useMemo } from 'react'
import { Crosshair, Flashlight, Waves } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useHighlight } from '@/components/fixtures-list/useHighlight'
import type { WriteTarget } from '@/components/fixtures-list/rowModel'
import { useLocateStateQuery, useToggleLocateMutation, type LocateTarget } from '@/store/locate'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { setBuskSheet } from '@/lib/buskWindow'
import { cn } from '@/lib/utils'
import type { BuskingTarget } from './buskingTypes'

/**
 * The busk view's three **selection verbs** — *Spread…*, Locate, Highlight — as one set of
 * handlers and one set of buttons, so the rig row and the pad row cannot answer a press two ways.
 *
 * They act on the selection and not on the tiles, which is why they are on both rows (busk-chrome
 * plan D17): the rig row's other controls — the Cells menu, the steps, Clear — narrow, move or
 * release a selection *made on the tiles*, and in Pads there are no tiles on screen, so they stay
 * on the rig screen; these three an operator expects on whichever screen they are pressing from,
 * and Spread is arguably the pads' own. `BuskingView` calls [useSelectionVerbs] once and hands the
 * result to whichever row is drawn — the band in Split and Rig, the pad row in Pads — so there is
 * one Highlight capture and one locate fold per window, never two.
 *
 * [SelectionVerbButtons] draws the three with the `aria-label`s and titles the band has always
 * used, an icon each and the word beside it folding away by the host's class: the rig row's rung
 * and the pad row's differ, and each row measures its own.
 */
export interface SelectionVerbs {
  /** *Spread…*: opens the side sheet's Spread tab, which reads the selection. */
  spread: () => void
  locate: {
    press: () => void
    /** Every selected target is located — the press then releases them. */
    active: boolean
    enabled: boolean
    title: string
    /** The compact verbs menu's item text: *Locate* or *Release locate*. */
    label: string
  }
  highlight: {
    press: () => void
    release: () => void
    active: boolean
    enabled: boolean
  }
}

export function useSelectionVerbs(selectedTargets: Map<string, BuskingTarget>): SelectionVerbs {
  const { fixtures } = useFixtureLookup()
  const locateTargets = useMemo<LocateTarget[]>(
    () =>
      [...selectedTargets.values()].map((target) => ({
        type: target.type,
        key: target.type === 'group' ? target.name : target.key,
      })),
    [selectedTargets],
  )
  const { data: locateState } = useLocateStateQuery()
  const [toggleLocate] = useToggleLocateMutation()
  // `?.` on the list too: a desk mid-boot answers the locate state before it has a list.
  const isLocated = useCallback(
    (target: LocateTarget) =>
      locateState?.targets?.some((t) => t.type === target.type && t.key === target.key) ?? false,
    [locateState],
  )
  const allLocated = locateTargets.length > 0 && locateTargets.every(isLocated)
  const locateSelection = useCallback(() => {
    const toToggle = allLocated ? locateTargets : locateTargets.filter((t) => !isLocated(t))
    for (const target of toToggle) {
      toggleLocate(target)
        .unwrap()
        .catch((err) => console.error(`Locate toggle failed for ${target.type} '${target.key}'`, err))
    }
  }, [allLocated, locateTargets, isLocated, toggleLocate])

  // Highlight lifts the selection's dimmers: a group's members from the fixture list, a fixture
  // itself, a cell its own element — the write targets `rowWriteTargets` would hand the toolbar.
  const getHighlightTargets = useCallback((): WriteTarget[] => {
    const out: WriteTarget[] = []
    for (const target of selectedTargets.values()) {
      if (target.type === 'group') {
        for (const fixture of fixtures ?? []) if (fixture.groups.includes(target.name)) out.push(fixture)
      } else if (target.element != null) out.push(target.element)
      else out.push(target.fixture)
    }
    return out
  }, [selectedTargets, fixtures])
  const highlight = useHighlight(getHighlightTargets)
  const anySelected = selectedTargets.size > 0

  return useMemo<SelectionVerbs>(
    () => ({
      spread: () => setBuskSheet('spread'),
      locate: {
        press: locateSelection,
        active: allLocated,
        enabled: locateTargets.length > 0,
        title: allLocated ? 'Release locate on the selection' : 'Locate the selection: white beam at centre',
        label: allLocated ? 'Release locate' : 'Locate',
      },
      highlight: {
        press: highlight.press,
        release: highlight.release,
        active: highlight.isActive,
        enabled: anySelected,
      },
    }),
    [locateSelection, allLocated, locateTargets.length, highlight.press, highlight.release, highlight.isActive, anySelected],
  )
}

/** One verb on a row: a 28px outline button with its icon, its word folding away first. */
export const VERB_CLASS = 'h-7 gap-1.5 px-2 text-xs'

/**
 * *Spread…* · Locate · Highlight, in that order, each the desk's ordinary outline button with its
 * icon (the programmer's toolbar draws Locate and Highlight with these two glyphs) and its word in
 * a span of [wordClass] — the host's fold. *Spread…* writes nothing but the sheet fact; below `md`
 * the overlay carries the tab, so the same write opens it there.
 *
 * **A held Highlight is released when these buttons unmount.** `useHighlight` releases on its
 * *own* host's unmount, and that host is now `BuskingView`, which outlives a focus change; the
 * buttons do not — a MIDI `buskFocusSet`, ⌘K or another window's `viewOptions` mid-hold takes the
 * band away with no `pointerup` ever reaching the held button, and the captured dimmers would stay
 * at full. The band hosted the hook itself before the verbs were lifted, so its unmount was the
 * release; this keeps that where the button is.
 */
export function SelectionVerbButtons({ verbs, wordClass }: { verbs: SelectionVerbs; wordClass: string }) {
  const { release } = verbs.highlight
  useEffect(() => release, [release])
  return (
    <>
      <Button
        variant="outline"
        size="sm"
        className={VERB_CLASS}
        onClick={verbs.spread}
        aria-label="Spread…"
        title="Spread a value across the selection"
      >
        <Waves className="size-3.5" />
        <span className={wordClass}>Spread…</span>
      </Button>
      <Button
        variant={verbs.locate.active ? 'default' : 'outline'}
        size="sm"
        className={cn(VERB_CLASS, verbs.locate.active && 'bg-sky-500 text-white hover:bg-sky-600')}
        onClick={verbs.locate.press}
        disabled={!verbs.locate.enabled}
        aria-label="Locate"
        title={verbs.locate.title}
      >
        <Crosshair className="size-3.5" />
        <span className={wordClass}>Locate</span>
      </Button>
      <Button
        variant={verbs.highlight.active ? 'default' : 'outline'}
        size="sm"
        className={VERB_CLASS}
        disabled={!verbs.highlight.enabled}
        onPointerDown={verbs.highlight.press}
        onPointerUp={verbs.highlight.release}
        onPointerCancel={verbs.highlight.release}
        onPointerLeave={verbs.highlight.release}
        aria-label="Highlight"
        title="Hold: every selected dimmer to full"
      >
        <Flashlight className="size-3.5" />
        <span className={wordClass}>Highlight</span>
      </Button>
    </>
  )
}
