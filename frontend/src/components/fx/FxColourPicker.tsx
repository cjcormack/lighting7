import { useCallback, useMemo, useRef, useState } from 'react'
import { Popover, PopoverTrigger, PopoverContent } from '@/components/ui/popover'
import { Label } from '@/components/ui/label'
import type { ColourReferences } from '@/components/editor/ColourEditorCore'
import {
  resolveColourToHex,
  parseExtendedColour,
  parsePaletteRefUuid,
  serializeExtendedColour,
  isPaletteRef,
  type ExtendedChannelFlags,
  type ExtendedColour,
} from './colourUtils'
import { FxColourEditor } from './FxColourEditor'
import { useColourPalettes } from './FxColourPalettes'
import { useRouteProjectId } from './useRouteProjectId'

interface FxColourPickerProps {
  value: string
  /**
   * [release] is false for a frame of a gesture and true for its end — the picker or a slider let
   * go, a field left, a recent or a palette picked — so the host's live push lands the last move.
   */
  onChange: (value: string, release: boolean) => void
  label?: string
  description?: string
  /** Which extended channels to show (based on target fixture capabilities) */
  extendedChannels?: Partial<ExtendedChannelFlags>
}

/**
 * One FX colour parameter: a swatch that opens the programmer's colour editor (venue-rig-fixes plan
 * D18) — `ColourEditorCore`, which is `ColourEditor` less its press bands — its Palettes band naming a
 * palette as the value.
 *
 * A reference opens at the colour the palette **currently** resolves to, so a drag starts from where
 * the rig is rather than from black — the rule Local's unset cells follow. Touching the colour then
 * replaces the reference with a literal, the honest reading of "I want this colour, not that
 * palette". An emitter the targets lack is never written, so a stored one is kept as it is.
 */
export function FxColourPicker({
  value,
  onChange,
  label,
  description,
  extendedChannels,
}: FxColourPickerProps) {
  const { palettes, labelFor, swatchFor } = useColourPalettes()
  const projectId = useRouteProjectId()
  const isRef = isPaletteRef(value)

  const [isOpen, setIsOpen] = useState(false)
  // The colour a gesture last wrote, for its release to land; null when nothing has moved since. A
  // press let go without a move sends nothing, so it never turns a reference into its literal.
  const dragged = useRef<ExtendedColour | null>(null)

  const refSwatch = isRef ? swatchFor(value) : null
  const colour = useMemo(() => parseExtendedColour(refSwatch ?? value), [refSwatch, value])
  const onColourChange = useCallback(
    (next: ExtendedColour) => {
      dragged.current = next
      onChange(serializeExtendedColour(next), false)
    },
    [onChange],
  )
  const onRelease = useCallback(() => {
    const landed = dragged.current
    dragged.current = null
    if (landed != null) onChange(serializeExtendedColour(landed), true)
  }, [onChange])

  const references = useMemo<ColourReferences>(
    () => ({
      palettes,
      selectedUuid: parsePaletteRefUuid(value),
      onPick: (ref) => {
        dragged.current = null
        onChange(ref, true)
      },
    }),
    [palettes, value, onChange],
  )

  const displayHex = refSwatch ?? resolveColourToHex(value)
  const refLabel = isRef ? labelFor(value) : null

  return (
    <div>
      {label && <Label className="text-xs mb-1.5 block">{label}</Label>}
      {description && (
        <p className="text-[11px] text-muted-foreground mb-1">{description}</p>
      )}
      <Popover
        open={isOpen}
        onOpenChange={(open) => {
          // An open starts with no gesture. Not a close: the editor lands what a close cut short.
          if (open) dragged.current = null
          setIsOpen(open)
        }}
      >
        <PopoverTrigger asChild>
          <button
            type="button"
            className="flex items-center gap-2 h-8 px-2 rounded-md border border-input bg-background text-xs hover:bg-accent/50 transition-colors"
          >
            <span
              className="w-5 h-5 rounded border border-border shrink-0"
              style={{ backgroundColor: displayHex }}
            />
            {/* A reference shows the palette's **name**, never a short code — same rule as
                `LookNameBadge`. The name is the whole point of referencing one. */}
            <span className={refLabel ? 'truncate max-w-[9rem]' : 'font-mono text-muted-foreground'}>
              {refLabel ?? displayHex}
            </span>
          </button>
        </PopoverTrigger>
        <PopoverContent className="w-[352px] p-3" align="start" side="right">
          <FxColourEditor
            colour={colour}
            extendedChannels={extendedChannels}
            onColourChange={onColourChange}
            onRelease={onRelease}
            open={isOpen}
            projectId={projectId}
            references={references}
          />
        </PopoverContent>
      </Popover>
    </div>
  )
}
