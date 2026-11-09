import type { ExtendedChannelFlags } from '@/components/fx/colourUtils'
import type { ColourAttributeDescriptor } from '@/store/fixtures'

/** The emitters a colour write can reach on a head. */
export type ColourEmitter = 'white' | 'amber' | 'uv'

/** A head as the emitter probe reads it: its own attributes and, for a fixture, its elements'. */
export interface EmitterHead {
  attributes: readonly { type: string }[]
  elements?: readonly { attributes: readonly { type: string }[] }[]
}

function colourDescriptorOf(attributes: readonly { type: string }[]): ColourAttributeDescriptor | null {
  return (attributes.find((p) => p.type === 'colour') as ColourAttributeDescriptor | undefined) ?? null
}

/**
 * How many of the targets take each emitter **through their colour descriptor**, and how many
 * take any — the one probe behind both the colour editor's emitter rows and its *Emitters on 6 of
 * 14 heads* line, so the two cannot disagree. Deliberately narrower than `targetEmitters`' probe,
 * which also counts a plain slider in an emitter category for the palette offer: a row for an
 * emitter a colour write cannot reach would be a slider that moves nothing.
 */
export function emitterHeadCounts<T extends EmitterHead>(targets: readonly T[]): Record<ColourEmitter | 'any', number> {
  const counts = { white: 0, amber: 0, uv: 0, any: 0 }
  for (const target of targets) {
    const descriptors = [target.attributes, ...(target.elements ?? []).map((e) => e.attributes)]
      .map(colourDescriptorOf)
      .filter((d): d is ColourAttributeDescriptor => d != null)
    const white = descriptors.some((d) => d.whiteChannel != null)
    const amber = descriptors.some((d) => d.amberChannel != null)
    const uv = descriptors.some((d) => d.uvChannel != null)
    if (white) counts.white += 1
    if (amber) counts.amber += 1
    if (uv) counts.uv += 1
    if (white || amber || uv) counts.any += 1
  }
  return counts
}

/**
 * The emitters any of [heads] takes through its colour, a Shehds' zones included — what an FX colour
 * parameter offers (venue-rig-fixes plan D19). Undefined where none takes one.
 */
export function emitterFlagsOf<T extends EmitterHead>(heads: readonly T[]): ExtendedChannelFlags | undefined {
  const counts = emitterHeadCounts(heads)
  if (counts.any === 0) return undefined
  return { white: counts.white > 0, amber: counts.amber > 0, uv: counts.uv > 0 }
}
