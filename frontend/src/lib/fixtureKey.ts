/**
 * The patch list's **key scheme**: how one typed key fans over a selection of Key cells, and in
 * what order the resulting PUTs can land without two heads holding one key at once.
 *
 * The scheme is the one `AddFixtureSheet` already mints keys in — `base`, a separator, a number —
 * read back off whatever the operator typed rather than imposed: a key that already ends in a
 * number continues *its* number, its separator and its zero padding, and one that does not gets
 * `-1`, `-2`, … appended. That is the whole of "match the scheme": nothing here invents a new
 * vocabulary, and nothing renames a head the operator did not select.
 *
 * The ordering half exists because the backend enforces key uniqueness **per PUT**
 * (`projectPatches.kt`: "Key '…' already exists"), unlike the address PUT, which checks nothing.
 * So a batch cannot be fired off in parallel the way a re-address is: `par-1 … par-4` re-keyed
 * from `par-2` walks onto keys its own members are still holding. [planKeyWrites] is the order
 * that never does.
 */

/** A key read as `base` + separator + a zero-padded number. */
export interface KeySuffix {
  /** Everything before the separator and the digits. */
  base: string
  /** What joins the two — `-`, `_`, a space, or nothing at all (`par1`). */
  separator: string
  number: number
  /** The digits as written, so `par-01` keeps padding to two. */
  width: number
}

/**
 * The trailing number of a key, or null where it has none.
 *
 * The base is lazy, so the *last* run of digits is the number: `foh-2b-10` is `foh-2b` + `-` + 10,
 * not `foh` + 2. `led-lightbar-12-pixel` has no trailing number and answers null.
 */
export function parseKeySuffix(key: string): KeySuffix | null {
  const match = /^(.*?)([-_ ]?)(\d+)$/.exec(key)
  if (!match) return null
  return { base: match[1], separator: match[2], number: Number(match[3]), width: match[3].length }
}

/** What a key with no number of its own is joined to its new one by — `AddFixtureSheet`'s. */
const DEFAULT_SEPARATOR = '-'

/**
 * The keys a typed key lands on over `count` heads, in visible-row order.
 *
 * One head takes the typed key exactly — re-keying a single fixture is a rename, not a scheme.
 * Several count up from it.
 */
export function spreadKeys(typed: string, count: number): string[] {
  if (count <= 0) return []
  if (count === 1) return [typed]
  const parsed = parseKeySuffix(typed)
  const base = parsed ? parsed.base : typed
  const separator = parsed ? parsed.separator : DEFAULT_SEPARATOR
  const first = parsed ? parsed.number : 1
  const width = parsed ? parsed.width : 1
  return Array.from({ length: count }, (_, i) =>
    `${base}${separator}${String(first + i).padStart(width, '0')}`,
  )
}

/** A head as the key arithmetic sees it. */
export interface KeyedHead {
  id: number
  key: string
  name: string
}

/** The preview of a re-key: one line per head, the first problem with the set, and the writes. */
export interface KeyLanding {
  /** `Front PAR → par-1`, one entry per head — drawn one to a line, like the address editor's. */
  lines: string[]
  /** Names the collision or the swap; null when every key lands clear. */
  error: string | null
  /**
   * The PUTs, in the order [planKeyWrites] found — **null exactly when [error] is set**, so the
   * caller that refuses on the error never has to ask twice. It is here rather than left to a
   * second call because checking a landing *is* planning it: the swap refusal below is "no order
   * exists", which nothing but the plan can answer.
   */
  writes: KeyWrite[] | null
}

/** One PUT: this patch takes this key. */
export interface KeyWrite {
  id: number
  key: string
}

/**
 * Check a proposed set of keys for the batch against every patch on the project.
 *
 * Two refusals, both named **before Apply** — the patch list's rule, the same one the Address
 * column makes:
 *
 *  - a target that collides with a head **outside** the batch, named with whose key it is;
 *  - a set the PUTs cannot be *ordered* into, which is a swap among the batch's own members
 *    ([planKeyWrites]). A target merely *held* by a batch member is fine — that is what the
 *    ordering is for.
 */
export function checkKeyLanding(
  batch: readonly KeyedHead[],
  targets: readonly string[],
  allPatches: readonly { id: number; key: string; displayName: string }[],
): KeyLanding {
  const lines = batch.length > 1 ? batch.map((head, i) => `${head.name} → ${targets[i]}`) : []
  const inBatch = new Set(batch.map((head) => head.id))
  for (const target of targets) {
    if (target.trim() === '') return { lines, error: 'A key cannot be empty', writes: null }
    const holder = allPatches.find((patch) => patch.key === target && !inBatch.has(patch.id))
    if (holder) {
      return { lines, error: `“${target}” is already ${holder.displayName}'s key`, writes: null }
    }
  }
  // A target held by a batch member is only an *ordering* problem — until the members' keys
  // permute among themselves with no free one to start from, which no order can untangle in a
  // world where each PUT is checked on its own.
  const writes = planKeyWrites(batch, targets, allPatches.map((patch) => patch.key))
  if (writes == null) {
    return {
      lines,
      error: 'Those heads would swap keys — re-key them onto a free name first, then back',
      writes: null,
    }
  }
  return { lines, error: null, writes }
}

/**
 * The PUTs that land `targets` on `batch`, **in an order where the key each one writes is free at
 * the moment it is written** — which is what the backend's per-PUT uniqueness check requires — or
 * **null where no such order exists**.
 *
 * Greedy: write whichever head's target nobody holds, which frees that head's old key for the
 * next. Re-keying `par-1 … par-4` from `par-2` therefore walks backwards, `par-4 → par-5` first.
 *
 * A genuine cycle — two heads swapping keys, which the visible order can produce when it is not
 * the key order — has no such head, and is **refused** rather than broken open by parking one of
 * them on a temporary key. Parking was built first and deleted: a PUT failing after a park leaves
 * a head on a synthetic `par-2-tmp1` on a live rig, with nothing to put it back, and the batch is
 * N independent requests so there is no transaction to lean on. A refusal the operator reads
 * before Apply costs them one extra step; the other costs them a fixture nobody can find.
 *
 * Pure, so `fixtureKey.test.ts` can state the order; the caller does the awaiting.
 */
export function planKeyWrites(
  batch: readonly KeyedHead[],
  targets: readonly string[],
  allKeys: readonly string[],
): KeyWrite[] | null {
  const taken = new Set(allKeys)
  const pending = batch
    .map((head, i) => ({ id: head.id, from: head.key, to: targets[i] }))
    .filter((step) => step.to !== step.from)
  const steps: KeyWrite[] = []
  while (pending.length > 0) {
    const landable = pending.findIndex((step) => !taken.has(step.to))
    // Every pass lands one head, so this terminates; nothing landable means a cycle.
    if (landable < 0) return null
    const [step] = pending.splice(landable, 1)
    taken.delete(step.from)
    taken.add(step.to)
    steps.push({ id: step.id, key: step.to })
  }
  return steps
}
