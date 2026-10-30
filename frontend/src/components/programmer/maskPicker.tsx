import { useEffect, useRef, useState } from 'react'
import { Label } from '@/components/ui/label'
import { ATTRIBUTE_FAMILIES, FAMILY_LABELS, formatFamilyList } from '@/lib/attributeFamily'
import { familiesKey, normaliseFamilies } from '@/lib/selectionMask'
import { cn } from '@/lib/utils'
import type { ProgrammerSkip } from '@/store/programmerOps'
import type { AttributeFamily } from '@/lib/attributeFamily'

/**
 * The four attribute families Record / Include / Update can be scoped to.
 *
 * Deliberately coarser than the backend's per-category `AttributeCategory`: the same physical
 * attribute is annotated differently across heads (a gobo wheel is a setting on one fixture
 * and a plain slider on another), so a category-level mask would silently miss fixtures. This
 * is the console I/P/C/B vocabulary, and it is four checkboxes rather than one per category.
 *
 * Derived from [ATTRIBUTE_FAMILIES] rather than restated: the rows *are* the families, in their
 * order, under the labels the Look library already uses. `maskPicker.test.ts` asserted both facts
 * while this list was hand-written; deriving it makes them true by construction and leaves the
 * test as the guard against someone un-deriving it.
 */
export const FAMILY_OPTIONS: { value: AttributeFamily; label: string }[] = ATTRIBUTE_FAMILIES.map(
  (family) => ({ value: family, label: FAMILY_LABELS[family].singular }),
)

/**
 * What a mask picker holds (venue-visit plan S10): `null` while *All attributes* is switched on, a
 * family list while it is off — the families ticked, in declaration order.
 *
 * Richer than the wire's mask on purpose. Switched off with all four ticked is the moment between
 * "every attribute" and a narrowing — the same mask as `null`, but a picker that folded it to `null`
 * would switch itself back on under the operator's hand. And an empty list is a state the wire has
 * no word for — a mask that lets nothing through — so hosts read it with [maskIsEmpty] and refuse
 * to act on it rather than send it. [maskToWire] is the one fold back to what a request carries.
 */
export type MaskValue = AttributeFamily[] | null

/** The mask a request carries: `undefined` for every attribute (switched on, or all four ticked). */
export function maskToWire(value: MaskValue): AttributeFamily[] | undefined {
  return normaliseFamilies(value) ?? undefined
}

/** Switched off with nothing ticked: no attribute at all, which nothing may be done under. */
export function maskIsEmpty(value: MaskValue): boolean {
  return value != null && value.length === 0
}

/** The sentence a host shows (or disables its action with) while [maskIsEmpty]. */
export const EMPTY_MASK_REASON = 'Pick at least one attribute.'

/**
 * A comparison key for a picker's mask as a request sees it — `''` for every attribute, `'-'` for
 * none, `COLOUR,POSITION` otherwise — so "has what we would send changed?" is one string test.
 */
export function maskValueKey(value: MaskValue): string {
  return maskIsEmpty(value) ? '-' : familiesKey(value)
}

/**
 * A live-writing host's draft of a stored mask, for a picker whose every change is written at once
 * (a layer's popover in `LookStack`, and the selection's family pill, `SelectionMaskPill`, which
 * writes the selection's mask the same way). The stored side folds "all four" to none and cannot hold "none", so a
 * picker fed straight from it would switch *All attributes* back on the moment the operator switched
 * it off. The draft holds the picker's own state, writes only a change to the folded mask and never
 * an empty one, and follows the stored mask when it moves to something the draft does not say — a
 * write from elsewhere.
 *
 * **Its own writes are told apart from those by the keys it sent and has not yet seen back**
 * (`pending`, oldest first). An echo of one of them is ours: it and every older one are dropped, and
 * the draft is left alone, since a later write is still on its way. Anything else is a write from
 * elsewhere. And a change is compared with the newest key sent, not the stored one, so a gesture made
 * before the last write's echo — unticking Colour then ticking it straight back — is still written
 * rather than mistaken for no change.
 */
export function useMaskDraft(
  stored: readonly AttributeFamily[] | null,
  write: (families: AttributeFamily[] | null) => void,
): [MaskValue, (next: MaskValue) => void] {
  const storedKey = familiesKey(stored)
  const [draft, setDraft] = useState<MaskValue>(() => normaliseFamilies(stored))
  const pendingRef = useRef<string[]>([])
  const storedRef = useRef(stored)
  storedRef.current = stored
  // An effect rather than a render-time adjustment: it reads and trims a ref, which a render must not
  // do (StrictMode renders twice), and it must run once per change of the stored mask.
  useEffect(() => {
    const pending = pendingRef.current
    const ours = pending.indexOf(storedKey)
    if (ours >= 0) {
      pendingRef.current = pending.slice(ours + 1)
      return
    }
    pendingRef.current = []
    setDraft((current) =>
      maskValueKey(current) === storedKey ? current : normaliseFamilies(storedRef.current),
    )
  }, [storedKey])
  const change = (next: MaskValue) => {
    setDraft(next)
    const sent = pendingRef.current[pendingRef.current.length - 1] ?? storedKey
    const key = maskValueKey(next)
    if (maskIsEmpty(next) || key === sent) return
    pendingRef.current = [...pendingRef.current, key]
    write(normaliseFamilies(next))
  }
  return [draft, change]
}

export interface MaskTogglesProps {
  value: MaskValue
  onChange: (next: MaskValue) => void
  /** Per-family counts beside each checkbox — see [MaskPickerProps.counts]. */
  counts?: Partial<Record<AttributeFamily, number>>
  className?: string
}

/**
 * The mask's control (venue-visit plan S10): an *All attributes* switch, and — only while it is
 * off — a checkbox per family.
 *
 * **On** is every attribute and shows no families: there is nothing to choose. **Switching it off
 * ticks all four**, so that is not yet a narrowing; the operator unticks what the write should leave
 * alone. The switch moves only when pressed: unticking the last family leaves it off with nothing
 * ticked ([maskIsEmpty], which the host refuses to act on), and ticking all four back leaves it off
 * too — nothing vanishes under the pointer. Switching it on again drops the ticks.
 *
 * Split out of [MaskPicker], which adds the label and the sentence under it, so that a host with no
 * room for either can mount the control alone; nothing here knows about the selection.
 */
export function MaskToggles({ value, onChange, counts, className }: MaskTogglesProps) {
  const all = value == null
  const toggle = (group: AttributeFamily) => {
    const ticked = value ?? []
    const next = ticked.includes(group) ? ticked.filter((g) => g !== group) : [...ticked, group]
    onChange(ATTRIBUTE_FAMILIES.filter((family) => next.includes(family)))
  }

  return (
    <div className={cn('space-y-2', className)}>
      <label className="flex w-fit cursor-pointer items-center gap-2 text-sm">
        <button
          type="button"
          role="switch"
          aria-checked={all}
          aria-label="All attributes"
          onClick={() => onChange(all ? [...ATTRIBUTE_FAMILIES] : null)}
          className={cn(
            'relative inline-flex h-5 w-9 shrink-0 items-center rounded-full border-2 border-transparent transition-colors',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
            all ? 'bg-primary' : 'bg-input',
          )}
        >
          <span
            className={cn(
              'pointer-events-none block size-4 rounded-full bg-background shadow transition-transform',
              all ? 'translate-x-4' : 'translate-x-0',
            )}
          />
        </button>
        All attributes
      </label>
      {!all && (
        <div role="group" aria-label="Attribute families" className="flex flex-wrap items-center gap-x-3 gap-y-2">
          {FAMILY_OPTIONS.map((group) => (
            <label key={group.value} className="flex items-center gap-1.5 text-sm">
              <input
                type="checkbox"
                checked={value.includes(group.value)}
                onChange={() => toggle(group.value)}
                className="size-4"
              />
              {group.label}
              {counts && (
                // Shown as 0 rather than hidden: "this family has nothing in it" is the useful
                // half of the answer, and an absent number reads as "not counted".
                <span className="tabular-nums text-xs text-muted-foreground">
                  ({counts[group.value] ?? 0})
                </span>
              )}
            </label>
          ))}
        </div>
      )}
    </div>
  )
}

export interface MaskPickerProps {
  /** As [MaskTogglesProps.value]: `null` is every attribute. */
  value: MaskValue
  onChange: (next: MaskValue) => void
  /**
   * How many values the **whole programmer** holds per family, when the caller can say.
   *
   * Optional because only `RecordLookSheet` needs it: a cue or palette record had its attribute
   * implied by its destination, but a Look has no type, so an unmasked record of a busked state
   * quietly captures position and beam alongside the colour that was meant. Showing the counts is
   * how that becomes visible before it happens rather than after.
   *
   * **Not a count of what the record will write**, and the label below says so. They cannot be:
   * the selection narrows the write, and a group-addressed entry's expansion into fixtures is
   * server-side, so a client-side filter would silently drop those entries rather than narrow
   * honestly. Which *families* are in play is the signal that matters here, and it survives the
   * narrowing; the magnitude does not.
   *
   * Drawn beside the family checkboxes, so only while *All attributes* is off.
   */
  counts?: Partial<Record<AttributeFamily, number>>
  /** Added to the sentence under the control, for a host whose mask means something narrower. */
  hint?: string
}

/**
 * The mask as a form field: a label, [MaskToggles], and a sentence saying what is masked. Every
 * sheet that takes a mask mounts this — Record, Record Look (the busk Spread sheet's *Save as
 * Look…* included), Include, Make layer, Update — and a layer's popover in `LookStack`, through
 * [useMaskDraft]. Each holds a [MaskValue], opens on the selection's mask (`useWindowMaskReader`;
 * Record, Record Look and Update on every attribute, `LookStack`'s on the layer's own), sends [maskToWire] and refuses to act while [maskIsEmpty]. A
 * change there is the sheet's own and never moves the selection.
 */
export function MaskPicker({ value, onChange, counts, hint }: MaskPickerProps) {
  const families = normaliseFamilies(value)
  const empty = maskIsEmpty(value)
  return (
    <div className="space-y-2">
      <Label>Attributes</Label>
      <MaskToggles value={value} onChange={onChange} counts={counts} />
      <p className={cn('text-xs', empty ? 'text-destructive' : 'text-muted-foreground')}>
        {empty
          ? EMPTY_MASK_REASON
          : families == null
            ? value == null
              ? 'All attributes.'
              : 'All four attributes — untick to narrow.'
            : `Only ${formatFamilyList(families, ' and ')}.`}
        {!empty && hint != null && ` ${hint}`}
        {/* Switched on, the families' checkboxes — and the counts beside them — are hidden, so the
            sentence carries the counts: which families are in play is the warning they exist for. */}
        {!empty && counts && value == null && ` ${heldSentence(counts)}`}
        {!empty && counts && value != null && ' Counts are what the programmer holds, before the selection narrows it.'}
      </p>
    </div>
  )
}

/** `The programmer holds Intensity (3) and Colour (4).`, or that it holds nothing, from [counts]. */
function heldSentence(counts: Partial<Record<AttributeFamily, number>>): string {
  const held = ATTRIBUTE_FAMILIES.filter((family) => (counts[family] ?? 0) > 0).map(
    (family) => `${FAMILY_LABELS[family].singular} (${counts[family]})`,
  )
  return held.length === 0
    ? 'The programmer holds no values.'
    : `The programmer holds ${held.length === 1 ? held[0] : `${held.slice(0, -1).join(', ')} and ${held[held.length - 1]}`}.`
}

const SKIP_REASONS: Record<ProgrammerSkip['reason'], string> = {
  ELEMENT_TARGET: 'element-level entries (this destination addresses whole fixtures)',
  MISSING_FIXTURE: 'fixtures no longer in the patch',
  MISSING_ATTRIBUTE: 'attributes that no longer resolve',
  NO_BACKING_ATTRIBUTE: 'raw channels with no backing attribute',
  MASKED_OUT: 'entries outside the attribute mask',
  OUT_OF_SCOPE: 'entries outside the selection',
}

/**
 * One line summarising what a write skipped, or null when nothing was.
 *
 * Silent truncation reads as "recorded everything", which is the failure mode this whole
 * session exists to remove — so the counts are surfaced even when they are boring.
 */
export function describeSkips(skips: readonly ProgrammerSkip[]): string | null {
  if (skips.length === 0) return null
  const byReason = new Map<ProgrammerSkip['reason'], number>()
  for (const skip of skips) byReason.set(skip.reason, (byReason.get(skip.reason) ?? 0) + 1)
  const parts = [...byReason.entries()].map(([reason, count]) => `${count} ${SKIP_REASONS[reason]}`)
  return `Skipped: ${parts.join('; ')}.`
}
