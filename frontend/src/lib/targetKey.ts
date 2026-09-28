/**
 * The one spelling of the `type:key` string that identifies a fixture-or-group target.
 *
 * Cue targets, locate targets and busking targets are three unions over the same two kinds of
 * thing, and every surface that dedupes or keys a list of them wants the same string. Normalise to
 * `{ type, key }` and encode here rather than hand-spelling the template literal — the busking
 * union in particular carries `name` where the others carry `key`, which is exactly the sort of
 * difference a hand-spelled literal gets wrong (see `buskingTargetKey`).
 */
export function targetKey(target: { type: string; key: string }): string {
  return `${target.type}:${target.key}`
}
