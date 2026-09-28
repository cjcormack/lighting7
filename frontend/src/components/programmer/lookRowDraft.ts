import { lookRowKey, splitLookRowKey } from './lookRowKey'
import type { LookRow } from '@/api/looksApi'

/**
 * Pending edits to the focused layer's Look, keyed the way the grid addresses a cell.
 *
 * Plain class rather than React state, and **per-key** notification rather than one version
 * counter, for the reason `useRowOwnership` splits its subscriptions: a cell drag commits at ~30 Hz
 * and a shared counter would repaint every visible row on every tick. Here each write wakes exactly
 * the cell that changed.
 *
 * Values are the canonical assignment strings (`"0".."255"`, `"#rrggbb;w128"`, `"pan,tilt"`) —
 * the same grammar a stored `LookRow.value` uses, so [applyTo] is a substitution rather than a
 * conversion.
 */
export class LookRowDraft {
  private values = new Map<string, string>()
  private listeners = new Map<string, Set<() => void>>()
  private wholeListeners = new Set<() => void>()

  get size(): number {
    return this.values.size
  }

  set(targetKey: string, propertyName: string, value: string): void {
    const key = lookRowKey(targetKey, propertyName)
    if (this.values.get(key) === value) return
    this.values.set(key, value)
    this.notify(key)
  }

  get(targetKey: string, propertyName: string): string | undefined {
    return this.values.get(lookRowKey(targetKey, propertyName))
  }

  /**
   * Forget entries the server now agrees with.
   *
   * Called when fresh `LookDetails` land, and the reason the draft is not simply cleared on a
   * successful save: between the PUT resolving and the refetch arriving there is a window in which
   * the cache still holds the old rows, and dropping the overlay inside it would flash the previous
   * value. Retiring an entry only once the server states it also lets another desk's edit through,
   * which a permanently-sticky overlay would hide.
   */
  reconcile(serverValues: ReadonlyMap<string, string>): void {
    for (const [key, value] of this.values) {
      if (serverValues.get(key) === value) {
        this.values.delete(key)
        this.notify(key)
      }
    }
  }

  clear(): void {
    const keys = [...this.values.keys()]
    this.values.clear()
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

  /** Wake on *any* change — for the save-state chrome, which is about the draft as a whole. */
  subscribeAll(listener: () => void): () => void {
    this.wholeListeners.add(listener)
    return () => this.wholeListeners.delete(listener)
  }

  /**
   * The Look's rows with the draft applied — the payload for a save.
   *
   * Built from whatever rows are passed in **at call time**, never from a snapshot taken when the
   * drag began: `PUT /looks/{id}` replaces the whole array, so a stale base would resend another
   * operator's rows as we last happened to see them.
   *
   * A draft entry always lands as a **fixture** row, even where a group row covered the same
   * property. That is the specificity the display already applies, so the cell the operator was
   * looking at and the row this writes agree; leaving the group row in place beneath it is correct
   * — the layer's other members keep it.
   */
  applyTo(serverRows: readonly LookRow[]): LookRow[] {
    if (this.values.size === 0) return [...serverRows]
    const pending = new Map(this.values)
    const out: LookRow[] = []
    for (const row of serverRows) {
      if (row.targetType === 'fixture' && !row.elementKey) {
        const key = lookRowKey(row.targetKey, row.propertyName)
        const value = pending.get(key)
        if (value !== undefined) {
          pending.delete(key)
          out.push({ ...row, value })
          continue
        }
      }
      out.push(row)
    }
    for (const [key, value] of pending) {
      const parts = splitLookRowKey(key)
      if (!parts) continue
      out.push({
        targetType: 'fixture',
        targetKey: parts.targetKey,
        propertyName: parts.propertyName,
        value,
      })
    }
    return out
  }

  private notify(key: string): void {
    this.listeners.get(key)?.forEach((listener) => listener())
    this.wholeListeners.forEach((listener) => listener())
  }
}
