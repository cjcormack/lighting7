import { Badge } from '@/components/ui/badge'
import { AudioWaveform } from 'lucide-react'
import { useActiveEffectsQuery } from '@/store/fixtureFx'

type FxBadgeProps =
  | { fixtureKey: string; fixtureGroups: string[]; groupName?: never }
  | { groupName: string; fixtureKey?: never; fixtureGroups?: never }

/**
 * "N FX" on a fixture or group card.
 *
 * Reads the rig-wide `fx/active` list rather than `fx/fixture/{key}` or
 * `groups/{name}/fx/active`, because every mounted badge shares that one cache entry: a single
 * `fxChanged` broadcast invalidates `FixtureEffects` wholesale, so per-target queries turned one
 * effect change into one GET per card on screen.
 */
export function FxBadge(props: FxBadgeProps) {
  const { fixtureKey, fixtureGroups, groupName } = props
  const { data: activeEffects } = useActiveEffectsQuery()

  const matching = (activeEffects ?? []).filter((effect) => {
    if (groupName) return effect.isGroupTarget && effect.targetKey === groupName
    if (!fixtureKey) return false
    // The two sets `GET /fx/fixture/{key}` reports: `direct` effects on the fixture itself, and
    // the `indirect` group effects that reach it through membership. `fixtureGroups` is the
    // fixture DTO's `groups`, which the backend fills from the same `groupsForFixture` the
    // indirect derivation uses, so the counts agree.
    return effect.isGroupTarget
      ? fixtureGroups.includes(effect.targetKey)
      : effect.targetKey === fixtureKey
  })

  if (matching.length === 0) return null

  const anyRunning = matching.some((e) => e.isRunning)

  return (
    <Badge variant={anyRunning ? 'default' : 'secondary'} className="text-xs gap-1">
      <AudioWaveform className="size-3" />
      {matching.length} FX
    </Badge>
  )
}
