import type { EffectDirection } from '@/api/fxApi'

/**
 * The pad face's words for an effect's direction and size (effects plan §4.1): `←` backward, `↔`
 * bounce, and the size where it is under 100 % — *Colour Cycle · 1 bar · M2 · ← · 60 %*. Forward
 * and full size say nothing, so a pad that uses neither reads as it always did.
 */
export function effectDynamicsParts(effect: { size?: number; direction?: EffectDirection }): string[] {
  const parts: string[] = []
  if (effect.direction === 'BACKWARD') parts.push('←')
  else if (effect.direction === 'BOUNCE') parts.push('↔')
  if (effect.size != null && effect.size < 1) parts.push(`${Math.round(effect.size * 100)} %`)
  return parts
}
