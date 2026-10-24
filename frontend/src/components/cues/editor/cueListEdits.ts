/**
 * The Looks and FX tabs' gestures as pure list edits (show-cue-editing plan §4.1). Each takes the
 * list as the desk holds it and answers the list to write — whole, every `sortOrder` restated from
 * its place, so a reorder can never leave two children tied (the order is the composition). The
 * store applies the same edit to the latest cue at flush, and the tab to what it draws meanwhile.
 */

interface Ordered {
  sortOrder?: number
}

/** Every child's `sortOrder` its place in the list. */
export function restated<T extends Ordered>(list: readonly T[]): T[] {
  return list.map((item, i) => (item.sortOrder === i ? item : { ...item, sortOrder: i }))
}

export function moveItem<T extends Ordered>(list: readonly T[], from: number, to: number): T[] | null {
  if (from === to || from < 0 || to < 0 || from >= list.length || to >= list.length) return null
  const next = [...list]
  const [moved] = next.splice(from, 1)
  next.splice(to, 0, moved)
  return restated(next)
}

export function removeAt<T extends Ordered>(list: readonly T[], index: number): T[] | null {
  if (index < 0 || index >= list.length) return null
  return restated(list.filter((_, i) => i !== index))
}

export function patchAt<T extends Ordered>(list: readonly T[], index: number, patch: Partial<T>): T[] | null {
  if (index < 0 || index >= list.length) return null
  return restated(list.map((item, i) => (i === index ? { ...item, ...patch } : item)))
}

export function replaceAt<T extends Ordered>(list: readonly T[], index: number, item: T): T[] | null {
  if (index < 0 || index >= list.length) return null
  return restated(list.map((existing, i) => (i === index ? item : existing)))
}

export function appendItem<T extends Ordered>(list: readonly T[], item: T): T[] {
  return restated([...list, item])
}

/** A child's identity on the desk: a layer's `id`, an effect's `uuid`; absent on one not yet saved. */
export type IdOf<T> = (item: T) => string | number | undefined

export const layerId: IdOf<{ id?: number }> = (layer) => layer.id
export const fxId: IdOf<{ uuid?: string }> = (effect) => effect.uuid

/**
 * Where the child the tab drew at [index] sits in [stored] — the cue as the desk holds it, which
 * another client's write may have moved past what the tab drew. By its identity where it has one,
 * and nowhere (-1) when the desk no longer holds it; a child not yet saved has none, and is found at
 * the place it was drawn.
 */
export function indexOfDrawn<T>(stored: readonly T[], drawn: T | undefined, index: number, idOf: IdOf<T>): number {
  if (drawn == null) return -1
  const id = idOf(drawn)
  if (id != null) return stored.findIndex((item) => idOf(item) === id)
  return index < stored.length ? index : -1
}

/** The drawn child at [index], taken out of [stored]. */
export function removeDrawn<T extends Ordered>(stored: readonly T[], drawn: readonly T[], index: number, idOf: IdOf<T>): T[] | null {
  return removeAt(stored, indexOfDrawn(stored, drawn[index], index, idOf))
}

/** The drawn child at [from] moved to where the drawn child at [to] sits in [stored]. */
export function moveDrawn<T extends Ordered>(stored: readonly T[], drawn: readonly T[], from: number, to: number, idOf: IdOf<T>): T[] | null {
  return moveItem(stored, indexOfDrawn(stored, drawn[from], from, idOf), indexOfDrawn(stored, drawn[to], to, idOf))
}

/** The drawn child at [index] changed in [stored], [change] applied to the desk's own copy of it. */
export function changeDrawn<T extends Ordered>(
  stored: readonly T[],
  drawn: readonly T[],
  index: number,
  idOf: IdOf<T>,
  change: (item: T) => T,
): T[] | null {
  const at = indexOfDrawn(stored, drawn[index], index, idOf)
  return at < 0 ? null : replaceAt(stored, at, change(stored[at]))
}
