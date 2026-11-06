import { useState, useCallback, useEffect, useRef } from 'react'
import { Popover, PopoverTrigger, PopoverContent } from '@/components/ui/popover'
import { Label } from '@/components/ui/label'
import {
  resolveColourToHex,
  parseExtendedColour,
  serializeExtendedColour,
  isPaletteRef,
  type ExtendedChannelFlags,
  type ExtendedColour,
} from './colourUtils'
import { useColourPalettes } from './FxColourPalettes'
import { ColourEditorBody } from './ColourEditorBody'

interface FxColourPickerProps {
  value: string
  /**
   * [release] is false for a frame of a drag and true for its end and for every edit that is one
   * already — a typed hex, a preset, a palette picked — so the host's live push lands the last move.
   */
  onChange: (value: string, release: boolean) => void
  label?: string
  description?: string
  /** Which extended channels to show (based on target fixture capabilities) */
  extendedChannels?: Partial<ExtendedChannelFlags>
}

export function FxColourPicker({
  value,
  onChange,
  label,
  description,
  extendedChannels,
}: FxColourPickerProps) {
  const { palettes: templates, labelFor, swatchFor } = useColourPalettes()
  const isRef = isPaletteRef(value)

  const [isOpen, setIsOpen] = useState(false)
  const [localColour, setLocalColour] = useState<ExtendedColour>(() => parseExtendedColour(value))
  // The colour a drag last drew, for its release to land; null when no drag has moved since. A press
  // let go without a move sends nothing, so it never turns a reference into its literal.
  const dragged = useRef<ExtendedColour | null>(null)

  // Sync from parent value when the popover opens.
  //
  // A reference opens at the colour the template *currently* resolves to, so dragging the picker
  // starts from where the rig is rather than from black — the same rule Local's unset cells follow.
  // Touching the picker then replaces the reference with a literal, which is the honest reading of
  // "I want this colour, not that template".
  useEffect(() => {
    if (isOpen) {
      const resolved = isPaletteRef(value) ? swatchFor(value) : null
      setLocalColour(parseExtendedColour(resolved ?? value))
    }
  }, [isOpen, value, swatchFor])

  // An open starts with no drag. Not a close: the body lands a drag the close cut short as it unmounts,
  // which an exit animation can put after this effect.
  useEffect(() => {
    if (isOpen) dragged.current = null
  }, [isOpen])

  const emitChange = useCallback(
    (colour: ExtendedColour, release: boolean) => {
      dragged.current = release ? null : colour
      setLocalColour(colour)
      onChange(serializeExtendedColour(colour), release)
    },
    [onChange]
  )
  const emitRelease = useCallback(() => {
    const colour = dragged.current
    dragged.current = null
    if (colour != null) onChange(serializeExtendedColour(colour), true)
  }, [onChange])

  const refSwatch = isRef ? swatchFor(value) : null
  const displayHex = refSwatch ?? resolveColourToHex(value)
  const refLabel = isRef ? labelFor(value) : null

  return (
    <div>
      {label && <Label className="text-xs mb-1.5 block">{label}</Label>}
      {description && (
        <p className="text-[11px] text-muted-foreground mb-1">{description}</p>
      )}
      <Popover open={isOpen} onOpenChange={setIsOpen}>
        <PopoverTrigger asChild>
          <button
            type="button"
            className="flex items-center gap-2 h-8 px-2 rounded-md border border-input bg-background text-xs hover:bg-accent/50 transition-colors"
          >
            <span
              className="w-5 h-5 rounded border border-border shrink-0"
              style={{ backgroundColor: displayHex }}
            />
            {/* A reference shows the template's **name**, never a short code — same rule as
                `LookNameBadge`. The name is the whole point of referencing one. */}
            <span className={refLabel ? 'truncate max-w-[9rem]' : 'font-mono text-muted-foreground'}>
              {refLabel ?? displayHex}
            </span>
          </button>
        </PopoverTrigger>
        <PopoverContent className="w-auto p-3" align="start" side="right">
          <ColourEditorBody
            colour={localColour}
            onColourChange={emitChange}
            onRelease={emitRelease}
            rawValue={value}
            onPickPalette={(ref) => {
              dragged.current = null
              onChange(ref, true)
            }}
            palettes={templates}
            extendedChannels={extendedChannels}
          />
        </PopoverContent>
      </Popover>
    </div>
  )
}
