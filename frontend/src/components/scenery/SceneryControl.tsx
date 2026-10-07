import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { X } from 'lucide-react'
import { Slider } from '@/components/ui/slider'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { EditorField } from '@/components/editor/EditorField'
import { EditorLabel } from '@/components/editor/EditorLabel'
import { EditorReadout } from '@/components/editor/EditorReadout'
import { useEditorForm } from '@/components/editor/EditorSurface'
import { useLivePush } from '@/components/editor/useLivePush'
import {
  presetOf,
  rangeFieldValue,
  rangeFromField,
  sameState,
  sceneryPresetsOf,
  sceneryRangeOf,
} from '@/lib/scenery'
import { cn } from '@/lib/utils'
import type { SceneryState } from '@/api/sceneryApi'
import type { SceneryKey } from '@/lib/scenery'
import type { StageElementDto } from '@/api/stageElementApi'

/** The floor between two live writes: the sheet's ~30 Hz commit throttle (`useSheet`), the kit's D16. */
export const SCENERY_PUSH_MS = 33

/**
 * How long a value the operator moved is drawn over what the host says, waiting for the desk's echo
 * to catch up — long enough for a round trip and a `scenery.state` broadcast, short enough that a
 * refused write is seen to snap back.
 */
const OVERRIDE_HOLD_MS = 1500

export interface SceneryControlProps {
  element: StageElementDto
  /**
   * What the control draws: the element's state **whole**, every key its kind takes
   * (`shownSceneryState`) — a control with a missing `open` would have no thumb to draw.
   */
  state: SceneryState
  /**
   * One write: the keys moved since the gesture began, nothing else, so a host that merges (the
   * programmer's overlay, a row's draft) keeps what this gesture did not touch. A slider's write
   * carries one key; a preset's its key and anything a drag in the same gesture left pending.
   */
  onWrite: (patch: SceneryState) => void
  /**
   * `live` (the default) writes as it goes through `useLivePush` — a 33 ms floor, deduplicated, and
   * a release that always lands — because the operator is watching the stage. `release` writes only
   * when the gesture ends: for a host whose write is a whole-list REST `PUT` (a cue's, a stack's or
   * a Look's scenery), where thirty saves a second would be thirty refetches of every cue.
   */
  commit?: 'live' | 'release'
  /** The line under the controls — what holds the piece (*held by the programmer*). */
  readout?: ReactNode
  disabled?: boolean
  /**
   * Draw the `EditorLabel` with the element's name. A host that names the element itself — a
   * `SceneryEditor` row, whose element select is the name — turns it off.
   */
  showLabel?: boolean
  /** Beside the label, after it: the kind's word, a release ×. */
  labelEnd?: ReactNode
  /** A stable id prefix for the controls' accessible names. */
  idPrefix?: string
  /**
   * The keys the host's own record **states** — a `SceneryEditor` row's — where the control draws
   * the whole state but the record says only part of it: a key it does not state is drawn muted (the
   * element's base, or what it tracks), and a stated one gets an *Unset* × through [onUnstate], so a
   * key once set can be let go again without deleting the row. Absent: every key is the host's own
   * to write, and nothing is unset (the programmer releases a whole element).
   */
  stated?: ReadonlySet<SceneryKey>
  /** Stop stating [key]. Offered only while the record states more than one key: a change of nothing is refused. */
  onUnstate?: (key: SceneryKey) => void
}

/**
 * One scene element's state, wherever it is edited (scenery-programmer plan D5, D17): a preset row
 * — today's editor steps, *Closed · Half · Drawn* or *In · Out* — a slider between them with its
 * field (`open` as %, `trimM` in m between the piece's in and its out), *Shown · Hidden*, and the
 * read-out saying what holds the piece. Built from the editor kit and carrying **no verbs and no
 * `EditorFooter`**: it writes as it goes, as the level and position editors do.
 *
 * **Which keys are drawn is the element's** (`sceneryKeysOf`, the desk's twin): a drawn drape's
 * `open`, a flown piece's `trimM`, and `visible` on every element. A flown piece whose stored trim
 * is its Z has nowhere to travel, so it offers *In* and no slider.
 *
 * **What it shows while the operator moves it is its own** for a moment ([OVERRIDE_HOLD_MS]): the
 * desk's echo lands a round trip later, and a thumb that jumped back to the old value under the
 * finger in between would read as a refusal. A key is let go the moment the host says the same.
 *
 * **Touch rows are the form's**: in either sheet form (`useEditorForm`) the presets grow to finger
 * height; a docked rail or a popover keeps the kit's 24px. Never a `sm:` variant.
 */
export function SceneryControl({
  element,
  state,
  onWrite,
  commit = 'live',
  readout,
  disabled = false,
  showLabel = true,
  labelEnd,
  idPrefix,
  stated,
  onUnstate,
}: SceneryControlProps) {
  const presets = useMemo(() => sceneryPresetsOf(element), [element])
  const range = useMemo(() => sceneryRangeOf(element), [element])
  const touch = useEditorForm() !== 'popover'

  // What the operator moved, drawn over [state] until the host catches up or the hold runs out.
  const [override, setOverride] = useState<SceneryState>({})
  const holdTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => {
    // A key the host now says the same about is the desk's again.
    setOverride((prev) => {
      const next: SceneryState = { ...prev }
      let changed = false
      for (const key of ['visible', 'open', 'trimM'] as const) {
        if (next[key] == null) continue
        if (sameState({ [key]: next[key] }, { [key]: state[key] })) {
          delete next[key]
          changed = true
        }
      }
      return changed ? next : prev
    })
  }, [state])
  useEffect(
    () => () => {
      if (holdTimer.current) clearTimeout(holdTimer.current)
    },
    [],
  )
  const shown: SceneryState = { ...state, ...override }

  // The gesture's own writes, merged — so a preset pressed while a drag's last move is still held by
  // the floor carries that move too, rather than the release dropping it (`flush` clears the timer).
  const gesture = useRef<SceneryState>({})
  const live = useLivePush<SceneryState>((patch) => onWrite(patch), { floorMs: SCENERY_PUSH_MS, equals: sameState })

  const hold = useCallback((patch: SceneryState) => {
    setOverride((prev) => ({ ...prev, ...patch }))
    if (holdTimer.current) clearTimeout(holdTimer.current)
    holdTimer.current = setTimeout(() => {
      holdTimer.current = null
      setOverride({})
    }, OVERRIDE_HOLD_MS)
  }, [])

  /** A move inside a running gesture (a drag): drawn now, written at the floor when live. */
  const move = (patch: SceneryState) => {
    hold(patch)
    gesture.current = { ...gesture.current, ...patch }
    if (commit === 'live') live.push({ ...gesture.current })
  }

  /** A gesture's end — a release, a preset, a typed value: written now, whatever the floor says. */
  const land = (patch: SceneryState) => {
    hold(patch)
    const value = { ...gesture.current, ...patch }
    gesture.current = {}
    live.flush(value)
    // The next gesture is a fresh one: dedupe it against nothing, since the piece may have moved
    // by every other route in between (a GO, another tab, Clear).
    live.reset()
  }

  const preset = presetOf(presets, shown)
  const name = idPrefix ?? element.uuid
  const travelKey = range?.key ?? (presets[0]?.state.open != null ? 'open' : presets[0]?.state.trimM != null ? 'trimM' : null)
  /** Muted where the host's record does not state [key]: what is drawn there is the base, or tracked. */
  const unstated = (key: SceneryKey | null) => stated != null && key != null && !stated.has(key)
  /** The *Unset* × after a group the record states, while it states something else as well. */
  const unset = (key: SceneryKey | null, what: string) =>
    stated != null && onUnstate != null && key != null && stated.has(key) && stated.size > 1 && !disabled ? (
      <button
        type="button"
        aria-label={`Unset ${element.name} ${what}`}
        title={`Stop setting the ${what} — track it instead`}
        className="inline-flex size-6 shrink-0 items-center justify-center rounded text-muted-foreground hover:bg-accent hover:text-foreground"
        onClick={() => onUnstate(key)}
      >
        <X className="size-3" />
      </button>
    ) : null
  const notStatedTitle = (what: string) => `Not set by this row — the ${what} tracks`

  return (
    <div data-scenery-control={element.uuid} className="min-w-0 space-y-1.5">
      {(showLabel || labelEnd != null) && (
        <div className="flex min-w-0 items-center gap-1.5">
          {showLabel && <EditorLabel className="min-w-0 truncate">{element.name}</EditorLabel>}
          {labelEnd}
        </div>
      )}
      <div className="flex flex-wrap items-center gap-1.5">
        {presets.length > 0 && (
          <ToggleGroup
            type="single"
            size="sm"
            aria-label={`${element.name} preset`}
            value={preset?.id ?? ''}
            disabled={disabled}
            onValueChange={(id) => {
              const chosen = presets.find((p) => p.id === id)
              if (chosen != null) land(chosen.state)
            }}
            title={unstated(travelKey) ? notStatedTitle('travel') : undefined}
            className={cn(touch && 'h-10', unstated(travelKey) && 'opacity-60')}
          >
            {presets.map((p) => (
              <ToggleGroupItem
                key={p.id}
                value={p.id}
                // A press on the lit item is a write too: Radix deselects it (`onValueChange('')`),
                // which writes nothing, and the state it shows may be the base or a cue's — holding
                // a piece where it already is, over a later cue, is a gesture the operator makes.
                onClick={() => {
                  if (!disabled && p.id === preset?.id) land(p.state)
                }}
                className={cn('text-xs', touch && 'h-8 px-3')}
              >
                {p.label}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
        )}
        {unset(travelKey, travelKey === 'open' ? 'opening' : 'trim')}
        <ToggleGroup
          type="single"
          size="sm"
          aria-label={`${element.name} visibility`}
          value={shown.visible === false ? 'hidden' : 'shown'}
          disabled={disabled}
          onValueChange={(v) => {
            if (v === 'shown' || v === 'hidden') land({ visible: v === 'shown' })
          }}
          title={unstated('visible') ? notStatedTitle('visibility') : undefined}
          className={cn(touch && 'h-10', unstated('visible') && 'opacity-60')}
        >
          {(['shown', 'hidden'] as const).map((v) => (
            <ToggleGroupItem
              key={v}
              value={v}
              // The lit item writes too — see the presets.
              onClick={() => {
                if (!disabled && (shown.visible === false ? 'hidden' : 'shown') === v) land({ visible: v === 'shown' })
              }}
              className={cn('text-xs', touch && 'h-8 px-3')}
            >
              {v === 'shown' ? 'Shown' : 'Hidden'}
            </ToggleGroupItem>
          ))}
        </ToggleGroup>
        {unset('visible', 'visibility')}
      </div>
      {range != null && (
        <div className={cn('flex items-center gap-2', unstated(range.key) && 'opacity-60')}>
          <Slider
            aria-label={`${element.name} ${range.key === 'open' ? 'open' : 'trim'}`}
            className={cn('min-w-0 flex-1', touch && 'py-2')}
            min={range.min}
            max={range.max}
            step={range.step}
            value={[shown[range.key] ?? range.min]}
            disabled={disabled}
            onValueChange={([v]) => {
              if (v != null) move({ [range.key]: v })
            }}
            onValueCommit={([v]) => {
              if (v != null) land({ [range.key]: v })
            }}
          />
          <EditorField
            label={`${name} ${range.key === 'open' ? 'open (%)' : 'trim (m)'}`}
            unit={range.unit}
            value={rangeFieldValue(range, shown[range.key] ?? range.min)}
            min={range.unit === '%' ? 0 : Math.round(range.min * 100) / 100}
            max={range.unit === '%' ? 100 : Math.round(range.max * 100) / 100}
            step={range.unit === '%' ? 1 : 0.1}
            disabled={disabled}
            onCommit={(typed) => land({ [range.key]: rangeFromField(range, typed) })}
            className="w-20 flex-none"
          />
        </div>
      )}
      {readout != null && <EditorReadout>{readout}</EditorReadout>}
    </div>
  )
}
