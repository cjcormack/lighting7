/**
 * Land the desk's literals for a focused Look layer in its draft — a spread's or a palette press's
 * `write: false` answer, each value on the head and property it resolved — and say how many landed.
 * A head the layer does not target is skipped, the store's own write guard made countable.
 */
export function landValuesInLook(
  store: {
    targetedKeys: ReadonlySet<string> | null
    setValue: (targetKey: string, propertyName: string, value: string) => void
  },
  values: readonly { target: { key: string }; propertyName: string; value: string }[],
): number {
  let landed = 0
  for (const value of values) {
    if (store.targetedKeys != null && !store.targetedKeys.has(value.target.key)) continue
    store.setValue(value.target.key, value.propertyName, value.value)
    landed += 1
  }
  return landed
}
