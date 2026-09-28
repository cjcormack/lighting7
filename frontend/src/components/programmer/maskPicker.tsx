import { Label } from '@/components/ui/label'
import { ATTRIBUTE_FAMILIES, FAMILY_LABELS } from '@/lib/attributeFamily'
import type { ProgrammerSkip, PropertyMaskGroup } from '@/store/programmerOps'

/**
 * The four attribute families Record / Include / Update can be scoped to.
 *
 * Deliberately coarser than the backend's 22 `PropertyCategory` values: the same physical
 * attribute is annotated differently across heads (a gobo wheel is a setting on one fixture
 * and a plain slider on another), so a category-level mask would silently miss fixtures. This
 * is the console I/P/C/B vocabulary, and it is four checkboxes rather than twenty-two.
 *
 * Derived from [ATTRIBUTE_FAMILIES] rather than restated: the rows *are* the families, in their
 * order, under the labels the Look library already uses. `maskPicker.test.ts` asserted both facts
 * while this list was hand-written; deriving it makes them true by construction and leaves the
 * test as the guard against someone un-deriving it.
 */
export const MASK_GROUPS: { value: PropertyMaskGroup; label: string }[] = ATTRIBUTE_FAMILIES.map(
  (family) => ({ value: family, label: FAMILY_LABELS[family].singular }),
)

export interface MaskPickerProps {
  value: PropertyMaskGroup[]
  onChange: (next: PropertyMaskGroup[]) => void
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
   */
  counts?: Partial<Record<PropertyMaskGroup, number>>
}

/** An empty selection means "everything" — the same thing the server does with an empty mask. */
export function MaskPicker({ value, onChange, counts }: MaskPickerProps) {
  const toggle = (group: PropertyMaskGroup) => {
    onChange(value.includes(group) ? value.filter((g) => g !== group) : [...value, group])
  }

  return (
    <div className="space-y-2">
      <Label>Attributes</Label>
      <div className="flex flex-wrap gap-3">
        {MASK_GROUPS.map((group) => (
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
      <p className="text-xs text-muted-foreground">
        {value.length === 0 ? 'All attributes.' : `Only ${value.length} of 4 attribute groups.`}
        {counts && ' Counts are what the programmer holds, before the selection narrows it.'}
      </p>
    </div>
  )
}

const SKIP_REASONS: Record<ProgrammerSkip['reason'], string> = {
  ELEMENT_TARGET: 'element-level entries (this destination addresses whole fixtures)',
  MISSING_FIXTURE: 'fixtures no longer in the patch',
  MISSING_PROPERTY: 'properties that no longer resolve',
  NO_BACKING_PROPERTY: 'raw channels with no backing property',
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
