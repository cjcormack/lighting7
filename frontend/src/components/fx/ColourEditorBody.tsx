import { useCallback, useState } from 'react'
import { HexColorPicker } from 'react-colorful'
import { ExtendedChannelSlider } from '../fixtures/ExtendedChannelSlider'
import {
  isValidHexColour,
  parseTemplateRefUuid,
  COLOUR_PRESETS,
  type ExtendedChannelFlags,
  type ExtendedColour,
} from './colourUtils'
import { FxColourTemplateRow } from './FxColourTemplates'
import type { TemplateSummary } from '@/api/templatesApi'

interface ColourEditorBodyProps {
  /**
   * The colour being edited, **already resolved**: a caller holding a `tmpl:` reference passes the
   * colour that template currently paints, not the placeholder behind it. That is what makes a
   * reference open at the colour the rig is showing rather than at black, and it is why this
   * component never resolves anything itself — the two callers hold references differently (one
   * value, or one item in a list) and only they know what a reference means there.
   */
  colour: ExtendedColour
  /**
   * An edit. The caller decides what it does to a reference; both of today's callers replace it
   * with the literal, which is the honest reading of "I want this colour, not that template".
   */
  onColourChange: (colour: ExtendedColour) => void
  /** The raw stored value, read only for which template chip is highlighted. */
  rawValue: string
  /** Pick a template: the caller receives a `tmpl:` reference string. */
  onPickTemplate: (ref: string) => void
  /** The offerable colour templates, from `useColourTemplates`. */
  templates: TemplateSummary[]
  /** Which extended channels the target fixtures actually have. */
  extendedChannels?: Partial<ExtendedChannelFlags>
}

/**
 * The five blocks inside a colour popover: wheel, hex field, presets, templates, extended channels.
 *
 * `FxColourPicker` (one colour) and `FxColourListPicker`'s swatches (an ordered list) render exactly
 * this, in this order, with the same commit guard on the hex field — so it lives once. What stays
 * with each picker is its **trigger and its seeding**: how the colour is stored, what a template
 * reference means there, and what an edit does to one.
 */
export function ColourEditorBody({
  colour,
  onColourChange,
  rawValue,
  onPickTemplate,
  templates,
  extendedChannels,
}: ColourEditorBodyProps) {
  const [hexInput, setHexInput] = useState(colour.hex)

  // The hex field mirrors the colour except while a half-typed value sits in it, and the mirror is
  // adjusted **during render** rather than in an effect. It has to be: `FxColourPicker` resolves a
  // template reference in its own open effect, so `colour` arrives one render after the popover
  // opens, and an effect here would run a frame behind it — repainting the wheel at the resolved
  // colour while the field beside it still read the unresolved one. This also covers the swatch
  // case an effect was there for, since it fires whenever the colour moves rather than only on
  // open: a reference whose template list arrived late updates the field without a remount.
  const [mirrored, setMirrored] = useState(colour.hex)
  if (colour.hex !== mirrored) {
    setMirrored(colour.hex)
    setHexInput(colour.hex)
  }

  const setHex = useCallback(
    (hex: string) => {
      setHexInput(hex)
      onColourChange({ ...colour, hex })
    },
    [colour, onColourChange],
  )

  // Typed hex is committed only once it parses, so the intermediate states of typing `#ff0000`
  // don't each get pushed up as a colour.
  const handleHexChange = useCallback(
    (hex: string) => {
      setHexInput(hex)
      const normalized = hex.startsWith('#') ? hex : `#${hex}`
      if (isValidHexColour(normalized)) {
        onColourChange({ ...colour, hex: normalized.toLowerCase() })
      }
    },
    [colour, onColourChange],
  )

  const handleExtendedChange = useCallback(
    (channel: 'white' | 'amber' | 'uv', val: number) => {
      onColourChange({ ...colour, [channel]: val })
    },
    [colour, onColourChange],
  )

  const hasExtended = extendedChannels?.white || extendedChannels?.amber || extendedChannels?.uv

  return (
    <div className="space-y-3">
      <HexColorPicker color={colour.hex} onChange={(hex) => setHex(hex.toLowerCase())} />

      <input
        type="text"
        value={hexInput}
        onChange={(e) => handleHexChange(e.target.value)}
        className="w-full h-7 px-2 text-xs font-mono rounded border border-input bg-background"
        spellCheck={false}
      />

      {/* Quick presets */}
      <div className="flex gap-1 flex-wrap">
        {COLOUR_PRESETS.map((preset) => (
          <button
            key={preset.hex}
            type="button"
            title={preset.name}
            className="w-5 h-5 rounded border border-border hover:scale-110 transition-transform"
            style={{ backgroundColor: preset.hex }}
            onClick={() => setHex(preset.hex)}
          />
        ))}
      </div>

      <FxColourTemplateRow
        templates={templates}
        currentHex={colour.hex}
        // The sliders below are part of the colour being dialled in, so *Save as template…* saves
        // them too — it used to keep the hex and silently drop the amber the operator had just set.
        currentEmitters={{ white: colour.white, amber: colour.amber, uv: colour.uv }}
        selectedUuid={parseTemplateRefUuid(rawValue)}
        onPick={onPickTemplate}
      />

      {/* Extended channels (W/A/UV) */}
      {hasExtended && (
        <div className="space-y-2 pt-2 border-t border-border">
          {extendedChannels?.white && (
            <ExtendedChannelSlider
              label="White"
              value={colour.white}
              onChange={(v) => handleExtendedChange('white', v)}
              color="#fffbe6"
            />
          )}
          {extendedChannels?.amber && (
            <ExtendedChannelSlider
              label="Amber"
              value={colour.amber}
              onChange={(v) => handleExtendedChange('amber', v)}
              color="#ffbf00"
            />
          )}
          {extendedChannels?.uv && (
            <ExtendedChannelSlider
              label="UV"
              value={colour.uv}
              onChange={(v) => handleExtendedChange('uv', v)}
              color="#7f00ff"
            />
          )}
        </div>
      )}
    </div>
  )
}
