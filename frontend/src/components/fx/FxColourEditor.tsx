import { ColourEditorCore, type ColourReferences } from '@/components/editor/ColourEditorCore'
import { computeCombinedCss } from '@/lib/colourMath'
import { hexToRgb, rgbToHex, type ExtendedChannelFlags, type ExtendedColour } from './colourUtils'

/**
 * The colour editor as an FX colour parameter mounts it (`FxColourPicker`, each swatch of
 * `FxColourListPicker`): the typed channels, no footer, white kept `#ffffff`, and only the emitters
 * the targets take — one the targets lack is shown nowhere and kept as stored.
 */
export function FxColourEditor({
  colour,
  extendedChannels,
  onColourChange,
  onRelease,
  open,
  projectId,
  references,
}: {
  colour: ExtendedColour
  extendedChannels?: Partial<ExtendedChannelFlags>
  onColourChange: (colour: ExtendedColour) => void
  onRelease: () => void
  open: boolean
  projectId: number | undefined
  references?: ColourReferences
}) {
  const rgb = hexToRgb(colour.hex)
  const white = extendedChannels?.white ? colour.white : undefined
  const amber = extendedChannels?.amber ? colour.amber : undefined
  const uv = extendedChannels?.uv ? colour.uv : undefined
  return (
    <ColourEditorCore
      r={rgb.r}
      g={rgb.g}
      b={rgb.b}
      w={white}
      a={amber}
      uv={uv}
      combinedCss={computeCombinedCss(rgb.r, rgb.g, rgb.b, white ?? 0, amber ?? 0, uv ?? 0)}
      hasWhiteChannel={!!extendedChannels?.white}
      hasAmberChannel={!!extendedChannels?.amber}
      hasUvChannel={!!extendedChannels?.uv}
      pickerWhiteLed={false}
      onColourChange={(r, g, b, w, a, u) =>
        onColourChange({
          hex: rgbToHex(r, g, b).toLowerCase(),
          white: w ?? colour.white,
          amber: a ?? colour.amber,
          uv: u ?? colour.uv,
        })
      }
      onRelease={onRelease}
      channelFields
      open={open}
      projectId={projectId}
      references={references}
      footer={false}
    />
  )
}
