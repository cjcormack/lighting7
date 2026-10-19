import type { FixtureColour } from './fixtureColours'

/**
 * The colour of whatever a surface names — a fixture, one of its heads, or a group — for the surfaces
 * that draw a target rather than a patch row: the busk rig's tiles and the MIDI surface's labels
 * (tables-colours plan D8, D9). A head takes its fixture's colour; a group its own, never a member's.
 * Built by `hooks/useTargetColours`.
 */
export interface TargetColours {
  /** By fixture key, and by each of its heads' element keys. */
  fixtures: ReadonlyMap<string, FixtureColour>
  /** By group name. */
  groups: ReadonlyMap<string, FixtureColour>
}

export const NO_TARGET_COLOURS: TargetColours = { fixtures: new Map(), groups: new Map() }

export function targetColour(
  target: { type: 'fixture' | 'group'; key: string } | null | undefined,
  colours: TargetColours,
): FixtureColour | null {
  if (target == null) return null
  return (target.type === 'group' ? colours.groups : colours.fixtures).get(target.key) ?? null
}
