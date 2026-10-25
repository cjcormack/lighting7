import { useSpeedMasterDisplay } from '@/store/speedMasters'
import { fxSpeedLabel } from '@/components/fx/fxConstants'
import { isAroundSpelling } from '@/components/fx/centreMode'
import { fxDynamicsParts } from '@/lib/fxDynamics'
import { isOnSeconds } from '@/lib/fxTiming'
import type { PaletteSummary } from '@/api/palettesApi'

/**
 * An effect template's detail line, which is **live** and therefore a component.
 *
 * The master's label comes from a subscription, and a hook cannot be conditional — so this cannot
 * be a string the caller builds, or every value pad in the bank would subscribe to the speed-master
 * bank to render a line that never mentions one. `padFace`'s static `detail` is the same line
 * without the master, and is what the drag ghost shows.
 */
export function FxPadDetail({ palette: template }: { palette: PaletteSummary }) {
  // A WALL_CLOCK effect never reads `speedMasterUuid`: its cycle is scaled by the *rate* master, and
  // a null one means **unscaled** rather than master 1.
  const isWallClock = template.fx?.timingSource === 'WALL_CLOCK'
  const master = useSpeedMasterDisplay(
    isWallClock ? template.fx?.rateSpeedMasterUuid : template.fx?.speedMasterUuid,
  )
  if (template.fx == null) return 'FX'
  const seconds = isOnSeconds(template.fx, template.fx.timingSource)
  const speed = fxSpeedLabel(template.fx.cycle, template.fx.timingSource, seconds)
  // A null `timingSource` means the stored `fxType` no longer resolves in this desk's registry.
  // Both clauses go then, not just the speed: `isWallClock` is false for a null as well as for a
  // beat effect, so naming the beat master would state a tempo link a wall-clock effect does not
  // have. Say nothing rather than pick the likelier of two wrong answers.
  // On Seconds (D7) there is no master to name: `4 s` says what it runs on.
  const masterLabel =
    template.fx.timingSource == null || seconds ? null : master ? `M${master.index}` : 'M1'
  // *around* (fixture-fx-sheets plan D16): the movement template orbits whatever holds the position
  // underneath — the one thing about a Circle pad an operator needs before pressing it after a
  // position pad. An Absolute one (every stored Override, call 6) says nothing.
  const around = isAroundSpelling(template.fx) ? 'around' : null
  // ← / ↔ and a size under 100 % (effects plan §4.1).
  return [template.fx.fxType, speed, masterLabel, around, ...fxDynamicsParts(template.fx)]
    .filter(Boolean)
    .join(' · ')
}
