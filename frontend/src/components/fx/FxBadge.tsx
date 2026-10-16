import { Badge } from '@/components/ui/badge'
import { AudioWaveform } from 'lucide-react'
import { useActiveEffectsQuery } from '@/store/fixtureFx'
import { effectNames } from '@/lib/effectTargets'

const NONE: ReadonlySet<string> = new Set()

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

  // The two sets `GET /fx/fixture/{key}` reports: `direct` effects on the fixture itself, and the
  // `indirect` ones — group effects that reach it through membership, and selection effects that
  // name it or one of its groups. `fixtureGroups` is the fixture DTO's `groups`, which the backend
  // fills from the same `groupsForFixture` the indirect derivation uses, so the counts agree.
  const keys = groupName ? NONE : new Set(fixtureKey ? [fixtureKey] : [])
  const groups = new Set(groupName ? [groupName] : (fixtureGroups ?? []))
  const matching = (activeEffects ?? []).filter((effect) => (groupName || fixtureKey) && effectNames(effect, keys, groups))

  if (matching.length === 0) return null

  const anyRunning = matching.some((e) => e.isRunning)

  return (
    <Badge variant={anyRunning ? 'default' : 'secondary'} className="text-xs gap-1">
      <AudioWaveform className="size-3" />
      {matching.length} FX
    </Badge>
  )
}
