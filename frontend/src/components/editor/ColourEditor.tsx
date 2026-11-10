import { useMemo } from 'react'
import type { PaletteTarget } from '@/api/palettesApi'
import type { AttributeFamily } from '@/lib/attributeFamily'
import { ColourEditorCore, type ColourEditorCoreProps, type ColourEmitter } from './ColourEditorCore'
import { FxBand, PaletteBand } from './PaletteBands'

export {
  ECHO_CEILING_MS,
  ECHO_SETTLE_MS,
  emitterFlagsOf,
  emitterHeadCounts,
  parseCssColour,
  type ColourBuffer,
  type ColourChannels,
  type ColourEmitter,
  type ColourReferences,
  type EmitterHead,
} from './ColourEditorCore'

/**
 * Where the **Palettes** band's palettes are pressed (apply-model plan §4.3, editor-kit D10). A host
 * that draws none passes nothing.
 */
export interface ColourPaletteSource {
  projectId: number
  /** Where a chip press lands — fixtures by key, a cell by its element key. */
  targets: readonly PaletteTarget[]
  /** The press's mask: *Colour* in the programmer's cell (the cell's own family), the busk pair's in its tab. */
  families: readonly AttributeFamily[] | null
  /** `usePress`'s scope — the busk tab passes `'programmer'`: it writes Local whatever the grid is pointed at. */
  scope?: 'window' | 'programmer'
}

export interface ColourEditorProps extends Omit<ColourEditorCoreProps, 'band' | 'fxBand' | 'references'> {
  /** The Palettes band. Absent, none is drawn. */
  palettes?: ColourPaletteSource
  /**
   * The Effects band, below the editor (apply-model plan §4.3) — the programmer's cell popup, and
   * docked under the Palettes band in the busk Colour tab. Absent, none is drawn.
   */
  effects?: ColourPaletteSource
}

/**
 * The colour editor (`ColourEditorCore`, which says what it is) with its two press bands: the
 * Palettes band and the Effects band, mounted only where a host asks for one — their store hooks
 * are the bands'.
 */
export function ColourEditor({ palettes, effects, ...core }: ColourEditorProps) {
  const { hasWhiteChannel, hasAmberChannel, hasUvChannel, docked } = core
  const emitters = useMemo<readonly ColourEmitter[]>(
    () => [...(hasWhiteChannel ? ['white' as const] : []), ...(hasAmberChannel ? ['amber' as const] : []), ...(hasUvChannel ? ['uv' as const] : [])],
    [hasWhiteChannel, hasAmberChannel, hasUvChannel],
  )
  const className = docked ? 'border-t px-3 pt-2 pb-2' : undefined
  const band =
    palettes == null ? undefined : (
      <PaletteBand
        projectId={palettes.projectId}
        family="COLOUR"
        targets={palettes.targets}
        families={palettes.families}
        emitters={emitters}
        scope={palettes.scope}
        className={className}
      />
    )
  const fxBand =
    effects == null ? undefined : (
      <FxBand
        projectId={effects.projectId}
        family="COLOUR"
        attribute="rgbColour"
        targets={effects.targets}
        families={effects.families}
        scope={effects.scope}
        className={className}
      />
    )
  return <ColourEditorCore {...core} band={band} fxBand={fxBand} />
}
