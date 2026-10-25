/**
 * Land the desk's literals for a focused Look layer in its draft — a spread's or a palette press's
 * `write: false` answer, each value on the head and attribute it resolved — and say how many landed.
 */
export function landValuesInLook(
  store: { setValue: (targetKey: string, attribute: string, value: string) => void },
  values: readonly { target: { key: string }; attribute: string; value: string }[],
): number {
  for (const value of values) store.setValue(value.target.key, value.attribute, value.value)
  return values.length
}
