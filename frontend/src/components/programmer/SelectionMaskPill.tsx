import { Filter } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { formatFamilyList, type AttributeFamily } from '@/lib/attributeFamily'
import { cn } from '@/lib/utils'
import { EMPTY_MASK_REASON, MaskToggles, maskIsEmpty, useMaskDraft } from './maskPicker'

export interface SelectionMaskPillProps {
  /** The selection's mask as it stands — `null` is every attribute. */
  families: AttributeFamily[] | null
  /**
   * Write a new mask on the selection, keeping its targets: `null` for every attribute. Called only
   * for a real change, never for "nothing ticked" (`useMaskDraft`).
   */
  onChange: (families: AttributeFamily[] | null) => void
  /**
   * `bar` is the sheet kit's plain outline badge (row C); `busk` the rig band's primary tint, worn
   * only while a mask is set — unmasked it is as quiet as the summary beside it.
   */
  tone: 'bar' | 'busk'
  className?: string
  /** A test handle the host's pill has always carried — `data-rig-family`, `data-pad-family`. */
  marker?: string
  /**
   * The face's words: whole (`Colour`), abbreviated (`Col`) or the glyph alone — the programmer's
   * row C at its narrower rungs. Default whole.
   */
  display?: 'full' | 'short' | 'icon'
}

const SHORT_FAMILY: Record<AttributeFamily, string> = {
  INTENSITY: 'Int',
  COLOUR: 'Col',
  POSITION: 'Pos',
  BEAM: 'Beam',
}

/** A mask's words at a rung: `Colour · Beam`, `Col · Beam`. */
export function maskWords(families: readonly AttributeFamily[], display: 'full' | 'short'): string {
  return display === 'full'
    ? formatFamilyList(families, ' · ')
    : families.map((family) => SHORT_FAMILY[family]).join(' · ')
}

/**
 * The selection's family pill as its **picker** (apply-model plan D3, §4.4): the pill reads the
 * mask every press from this selection carries, and pressing it opens S10's `MaskToggles` — the
 * *All attributes* switch, and the four families while it is off — over it.
 *
 * **The draft is the switch's, the write is the selection's.** The selection's `families` folds all
 * four ticked to "no mask" and has no word for "none ticked", so the toggles are fed through
 * `useMaskDraft`: switching *All attributes* off ticks all four and writes nothing, an untick
 * writes the narrowing, ticking all four back writes "no mask" while the switch stays off, and
 * unticking the last family writes nothing and says why. The pill's own face always reads the
 * stored mask — the fact the next press carries — never the draft.
 *
 * It knows nothing of which selection it masks: the host hands in [onChange], which keeps the
 * heads — on the programmer the list's desk bridge (`remask`), on the busk view the desk's
 * `selection.setFamilies` while following and the tab's own copy while not. A marquee and a
 * rows-only click still move the mask from the grid, and the draft follows them as a write from
 * elsewhere.
 */
export function SelectionMaskPill({ families, onChange, tone, className, marker, display = 'full' }: SelectionMaskPillProps) {
  const masked = families != null && families.length > 0
  const label = masked ? maskWords(families, display === 'short' ? 'short' : 'full') : 'All'
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Badge
          asChild
          variant="outline"
          className={cn(
            'cursor-pointer whitespace-nowrap py-0 text-[10px] hover:bg-accent focus-visible:outline-none',
            tone === 'bar' ? 'px-1.5' : 'px-2',
            tone === 'busk' && masked && 'border-primary/40 bg-primary/10 text-primary hover:bg-primary/15',
            !masked && 'text-muted-foreground',
            className,
          )}
        >
          <button
            type="button"
            {...(marker != null ? { [marker]: '' } : {})}
            title={
              masked
                ? `Attributes: ${formatFamilyList(families, ', ')} — every press on this selection is masked to them. Click to change.`
                : 'All attributes — click to mask the selection to some of them.'
            }
          >
            {display === 'icon' ? <Filter className="size-3" aria-label={`Mask: ${label}`} /> : label}
          </button>
        </Badge>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-64 space-y-2">
        <MaskDraft families={families} onChange={onChange} />
      </PopoverContent>
    </Popover>
  )
}

/**
 * The popover's body, mounted only while it is open, so **every open seeds its draft from the
 * stored mask** — `LayerMaskPicker`'s shape in `LookStack`. A draft held by the pill itself outlived
 * the popover: switched off with all four ticked, or with nothing ticked, it reopened disagreeing
 * with the face beside it.
 */
function MaskDraft({ families, onChange }: Pick<SelectionMaskPillProps, 'families' | 'onChange'>) {
  const [draft, setDraft] = useMaskDraft(families, onChange)
  const empty = maskIsEmpty(draft)
  const stays = families != null && families.length > 0 ? listWithAnd(families) : 'on every attribute'
  return (
    <>
      <MaskToggles value={draft} onChange={setDraft} />
      <p className={cn('text-xs', empty ? 'text-destructive' : 'text-muted-foreground')}>
        {empty ? `${EMPTY_MASK_REASON} The selection stays ${stays}.` : 'What every press on this selection may touch.'}
      </p>
    </>
  )
}

/** `Colour`, `Intensity and Colour`, `Intensity, Position and Colour`. */
function listWithAnd(families: readonly AttributeFamily[]): string {
  if (families.length <= 1) return formatFamilyList(families)
  return `${formatFamilyList(families.slice(0, -1), ', ')} and ${formatFamilyList(families.slice(-1))}`
}
