import { useSpeedMasterDisplay } from '@/store/speedMasters'
import { effectSpeedLabel } from '@/components/fx/fxConstants'
import type { TemplateSummary } from '@/api/templatesApi'

/**
 * An effect template's detail line, which is **live** and therefore a component.
 *
 * The master's label comes from a subscription, and a hook cannot be conditional — so this cannot
 * be a string the caller builds, or every value pad in the bank would subscribe to the speed-master
 * bank to render a line that never mentions one. `padFace`'s static `detail` is the same line
 * without the master, and is what the drag ghost shows.
 */
export function EffectPadDetail({ template }: { template: TemplateSummary }) {
  // A WALL_CLOCK effect never reads `speedMasterUuid`: its cycle is scaled by the *rate* master, and
  // a null one means **unscaled** rather than master 1.
  const isWallClock = template.effect?.timingSource === 'WALL_CLOCK'
  const master = useSpeedMasterDisplay(
    isWallClock ? template.effect?.rateSpeedMasterUuid : template.effect?.speedMasterUuid,
  )
  if (template.effect == null) return 'Effect'
  const speed = effectSpeedLabel(template.effect.beatDivision, template.effect.timingSource)
  // A null `timingSource` means the stored `effectType` no longer resolves in this desk's registry.
  // Both clauses go then, not just the speed: `isWallClock` is false for a null as well as for a
  // beat effect, so naming the beat master would state a tempo link a wall-clock effect does not
  // have. Say nothing rather than pick the likelier of two wrong answers.
  const masterLabel =
    template.effect.timingSource == null
      ? null
      : master
        ? `M${master.index}`
        : isWallClock && template.effect.rateSpeedMasterUuid == null
          ? 'unscaled'
          : 'M1'
  return [template.effect.effectType, speed, masterLabel].filter(Boolean).join(' · ')
}
