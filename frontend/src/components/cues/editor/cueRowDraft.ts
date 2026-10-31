import { parseProgrammerValue } from '@/lib/programmerValue'
import type { CueAttributeAssignment } from '@/api/cuesApi'
import type { StagedValue } from '@/components/fixtures-list/useRowOwnership'

/** A cue's own row is addressed by its target's kind as well as its key: a group row and a fixture row are two rows. */
export type CueRowTargetType = 'fixture' | 'group'

/** One pending edit to an own row: a value the operator set, or the row taken away (⌫). */
export type CueRowDraftEntry = { kind: 'set'; value: string } | { kind: 'removed' }

const SEP = '\u0000'

/**
 * How the cue grid addresses an own row: target type, target key and the **canonical** property,
 * NUL-joined (a fixture key may hold anything else — `lookRowKey`'s reason).
 */
export function cueRowKey(targetType: CueRowTargetType, targetKey: string, attribute: string): string {
  return `${targetType}${SEP}${targetKey}${SEP}${canonicalCueAttribute(attribute)}`
}

function splitCueRowKey(key: string): { targetType: CueRowTargetType; targetKey: string; attribute: string } | null {
  const parts = key.split(SEP)
  if (parts.length !== 3) return null
  const [targetType, targetKey, attribute] = parts
  if (targetType !== 'fixture' && targetType !== 'group') return null
  return { targetType, targetKey, attribute }
}

/**
 * The desk's `canonicalAttributeName` (`fx/PersistedFixtureReferenceValidator.kt`): the RGB colour is
 * stored under any of three spellings and composes as `rgbColour`. Mirrored so a row the cue holds as
 * `colour` and an edit the grid makes on `rgbColour` are one row, not two.
 */
export function canonicalCueAttribute(attribute: string): string {
  const lower = attribute.toLowerCase()
  return lower === 'colour' || lower === 'color' || lower === 'rgbcolour' ? 'rgbColour' : attribute
}

/** Two value strings say the same thing — compared parsed, since the desk may spell one differently. */
export function sameCueValue(a: string, b: string): boolean {
  if (a === b) return true
  return sameStaged(parseProgrammerValue(a), parseProgrammerValue(b))
}

function sameStaged(a: StagedValue | null, b: StagedValue | null): boolean {
  if (a == null || b == null) return false
  if (a.kind === 'level' && b.kind === 'level') return a.value === b.value
  if (a.kind === 'position' && b.kind === 'position') return a.pan === b.pan && a.tilt === b.tilt
  if (a.kind === 'colour' && b.kind === 'colour') {
    const z = (n: number | undefined) => n ?? 0
    return a.r === b.r && a.g === b.g && a.b === b.b && z(a.w) === z(b.w) && z(a.a) === z(b.a) && z(a.uv) === z(b.uv)
  }
  return false
}

/**
 * Pending edits to one cue's **own rows**, keyed as [cueRowKey] — `LookRowDraft`'s shape (per-key
 * notification, a plain class, canonical value strings), plus **removals**, which the Look draft
 * never needed: ⌫ takes an own row away so the layer under it shows again.
 *
 * An entry is retired only once the server agrees with it **and** a cook read after its save has
 * landed ([reconcile]): the cue's details and its `/cooked` rows refetch independently, and retiring
 * on the details alone would flash the old composed value for the moment the cook is still stale.
 */
export class CueRowDraft {
  private entries = new Map<string, CueRowDraftEntry>()
  /** When each entry's save resolved — the stamp a later cook read must beat to retire it. */
  private savedAt = new Map<string, number>()
  private listeners = new Map<string, Set<() => void>>()
  private wholeListeners = new Set<() => void>()

  get size(): number {
    return this.entries.size
  }

  set(targetType: CueRowTargetType, targetKey: string, attribute: string, value: string): void {
    this.put(cueRowKey(targetType, targetKey, attribute), { kind: 'set', value })
  }

  remove(targetType: CueRowTargetType, targetKey: string, attribute: string): void {
    this.put(cueRowKey(targetType, targetKey, attribute), { kind: 'removed' })
  }

  get(targetType: CueRowTargetType, targetKey: string, attribute: string): CueRowDraftEntry | undefined {
    return this.entries.get(cueRowKey(targetType, targetKey, attribute))
  }

  /** Every entry, by key — the pending edits a flush writes. */
  snapshot(): ReadonlyMap<string, CueRowDraftEntry> {
    return new Map(this.entries)
  }

  /**
   * The entries no save has carried yet — what a flush sends. An entry saved (or in flight) is in
   * the cue already, or about to be, and re-sending it would re-assert it over a later edit of that
   * row from elsewhere and replace Undo's base with a no-op.
   */
  unsaved(): ReadonlyMap<string, CueRowDraftEntry> {
    const out = new Map<string, CueRowDraftEntry>()
    for (const [key, entry] of this.entries) if (!this.savedAt.has(key)) out.set(key, entry)
    return out
  }

  /** The entries [sent] carries are on their way: no flush sends them again unless this one fails. */
  markSending(sent: ReadonlyMap<string, CueRowDraftEntry>): void {
    for (const [key, entry] of sent) {
      if (this.entries.get(key) === entry) this.savedAt.set(key, Number.POSITIVE_INFINITY)
    }
  }

  /** The entries [sent] carried were saved at [at]: a cook read after it may retire them. */
  markSaved(sent: ReadonlyMap<string, CueRowDraftEntry>, at: number): void {
    for (const [key, entry] of sent) {
      if (this.entries.get(key) === entry) this.savedAt.set(key, at)
    }
  }

  /** The save carrying [sent] failed: its entries are unsaved again, for the next flush. */
  markFailed(sent: ReadonlyMap<string, CueRowDraftEntry>): void {
    for (const [key, entry] of sent) {
      if (this.entries.get(key) === entry) this.savedAt.delete(key)
    }
  }

  /** Whether this key's entry has been sent — in flight or saved — so the cue holds it, or will. */
  isSent(key: string): boolean {
    return this.entries.has(key) && this.savedAt.has(key)
  }

  /** Forget these keys' entries — Undo putting their rows back. */
  discard(keys: Iterable<string>): void {
    for (const key of keys) {
      if (!this.entries.delete(key)) continue
      this.savedAt.delete(key)
      this.notify(key)
    }
  }

  /**
   * Forget the entries the server now states: a set whose own row holds that value, a removal whose
   * own row is gone — each only once [cookAt], the cook's fetch time, is after the entry's save.
   */
  reconcile(stored: readonly CueAttributeAssignment[], cookAt: number): void {
    const byKey = new Map<string, string[]>()
    for (const row of stored) {
      const key = cueRowKey(row.targetType, row.targetKey, row.attribute)
      const list = byKey.get(key)
      if (list) list.push(row.value)
      else byKey.set(key, [row.value])
    }
    for (const [key, entry] of [...this.entries]) {
      const saved = this.savedAt.get(key)
      // Unsaved, or still in flight (`markSending`'s infinity), or read back by a stale cook.
      if (saved == null || cookAt < saved) continue
      const values = byKey.get(key)
      const agrees =
        entry.kind === 'removed' ? values == null : values != null && values.every((v) => sameCueValue(v, entry.value))
      if (!agrees) continue
      this.entries.delete(key)
      this.savedAt.delete(key)
      this.notify(key)
    }
  }

  clear(): void {
    const keys = [...this.entries.keys()]
    this.entries.clear()
    this.savedAt.clear()
    for (const key of keys) this.notify(key)
  }

  /** Wake on any change to these keys. Returns the unsubscribe. */
  subscribe(keys: readonly string[], listener: () => void): () => void {
    for (const key of keys) {
      let set = this.listeners.get(key)
      if (!set) {
        set = new Set()
        this.listeners.set(key, set)
      }
      set.add(listener)
    }
    return () => {
      for (const key of keys) {
        const set = this.listeners.get(key)
        if (!set) continue
        set.delete(listener)
        if (set.size === 0) this.listeners.delete(key)
      }
    }
  }

  /** Wake on any change at all. */
  subscribeAll(listener: () => void): () => void {
    this.wholeListeners.add(listener)
    return () => this.wholeListeners.delete(listener)
  }

  private put(key: string, entry: CueRowDraftEntry): void {
    const prev = this.entries.get(key)
    if (prev && prev.kind === entry.kind && (prev.kind === 'removed' || (entry.kind === 'set' && prev.value === entry.value))) return
    this.entries.set(key, entry)
    this.savedAt.delete(key)
    this.notify(key)
  }

  private notify(key: string): void {
    this.listeners.get(key)?.forEach((listener) => listener())
    this.wholeListeners.forEach((listener) => listener())
  }
}

/**
 * The cue's `attributeAssignments` with [entries] applied — the whole list a flush PATCHes, built
 * from the cue **as it stands at flush time**, never from a snapshot taken when the edit began:
 * the PATCH replaces the list, so a stale base would resend another desk's rows as we last saw them.
 *
 * Every kept row keeps its `fadeDurationMs` and `sortOrder` (`FU-CUE-ROW-TIMING`: the
 * grid edits values only). A set lands on every stored row of its key — a key held twice is drawn as
 * one cell, and the cell must show what was typed — and a removal drops them all. A new row is
 * appended after the highest `sortOrder`.
 */
export function applyCueRowEntries(
  stored: readonly CueAttributeAssignment[],
  entries: ReadonlyMap<string, CueRowDraftEntry>,
): CueAttributeAssignment[] {
  if (entries.size === 0) return stored.map(stripRow)
  const out: CueAttributeAssignment[] = []
  const landed = new Set<string>()
  for (const row of stored) {
    const key = cueRowKey(row.targetType, row.targetKey, row.attribute)
    const entry = entries.get(key)
    if (entry == null) {
      out.push(stripRow(row))
      continue
    }
    landed.add(key)
    if (entry.kind === 'removed') continue
    out.push({ ...stripRow(row), value: entry.value })
  }
  let next = stored.reduce((max, row) => Math.max(max, row.sortOrder ?? 0), -1) + 1
  for (const [key, entry] of entries) {
    if (entry.kind !== 'set' || landed.has(key)) continue
    const parts = splitCueRowKey(key)
    if (parts == null) continue
    out.push({ ...parts, value: entry.value, sortOrder: next })
    next += 1
  }
  return out
}

/** A stored row in the shape the write takes — its own fields, nothing a read adds. */
function stripRow(row: CueAttributeAssignment): CueAttributeAssignment {
  const out: CueAttributeAssignment = {
    targetType: row.targetType,
    targetKey: row.targetKey,
    attribute: row.attribute,
    value: row.value,
  }
  if (row.fadeDurationMs !== undefined) out.fadeDurationMs = row.fadeDurationMs
  if (row.sortOrder !== undefined) out.sortOrder = row.sortOrder
  return out
}

/** The stored rows under [keys], as the write takes them — what a save replaced, kept for its Undo. */
export function rowsWithKeys(stored: readonly CueAttributeAssignment[], keys: readonly string[]): CueAttributeAssignment[] {
  const wanted = new Set(keys)
  return stored.filter((row) => wanted.has(cueRowKey(row.targetType, row.targetKey, row.attribute))).map(stripRow)
}

/**
 * The cue's list with the rows under [keys] put back to [rows] — Undo, as the exact inverse of the
 * save it undoes: every other row stays as the cue holds it **now**, so an edit to another row since
 * (here or from another client) survives. A restored row goes back to its own `sortOrder`, before
 * the first row ordered after it.
 */
export function restoreCueRows(
  stored: readonly CueAttributeAssignment[],
  keys: readonly string[],
  rows: readonly CueAttributeAssignment[],
): CueAttributeAssignment[] {
  const touched = new Set(keys)
  const out = stored.filter((row) => !touched.has(cueRowKey(row.targetType, row.targetKey, row.attribute))).map(stripRow)
  for (const row of rows) {
    const order = row.sortOrder
    const at = order == null ? -1 : out.findIndex((other) => other.sortOrder != null && other.sortOrder > order)
    if (at < 0) out.push({ ...row })
    else out.splice(at, 0, { ...row })
  }
  return out
}
