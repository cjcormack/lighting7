import { useState, useCallback, useEffect } from 'react'
import { Popover, PopoverTrigger, PopoverContent } from '@/components/ui/popover'
import { Label } from '@/components/ui/label'
import {
  resolveColourToHex,
  parseExtendedColour,
  serializeExtendedColour,
  isTemplateRef,
  type ExtendedChannelFlags,
  type ExtendedColour,
} from './colourUtils'
import { useColourTemplates } from './FxColourTemplates'
import { ColourEditorBody } from './ColourEditorBody'

interface FxColourPickerProps {
  value: string
  onChange: (value: string) => void
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
  const { templates, labelFor, swatchFor } = useColourTemplates()
  const isRef = isTemplateRef(value)

  const [isOpen, setIsOpen] = useState(false)
  const [localColour, setLocalColour] = useState<ExtendedColour>(() => parseExtendedColour(value))

  // Sync from parent value when the popover opens.
  //
  // A reference opens at the colour the template *currently* resolves to, so dragging the picker
  // starts from where the rig is rather than from black — the same rule Local's unset cells follow.
  // Touching the picker then replaces the reference with a literal, which is the honest reading of
  // "I want this colour, not that template".
  useEffect(() => {
    if (isOpen) {
      const resolved = isTemplateRef(value) ? swatchFor(value) : null
      setLocalColour(parseExtendedColour(resolved ?? value))
    }
  }, [isOpen, value, swatchFor])

  const emitChange = useCallback(
    (colour: ExtendedColour) => {
      setLocalColour(colour)
      onChange(serializeExtendedColour(colour))
    },
    [onChange]
  )

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
            rawValue={value}
            onPickTemplate={onChange}
            templates={templates}
            extendedChannels={extendedChannels}
          />
        </PopoverContent>
      </Popover>
    </div>
  )
}
