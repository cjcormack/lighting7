/**
 * How many of a Look's preview chips fit on one line — the Looks sheet's Preview cell, which used
 * to wrap its chips onto a second and third line inside a 36px row and paint them over the rows
 * above and below.
 *
 * [chipWidths] are the chips in order, [available] the line's width, [gap] the space between two
 * items, and [moreWidth] the `+N` that stands for the chips left out. The answer is the largest
 * count whose chips — and, when any are left out, the `+N` after them — fit; it can be 0, when not
 * even one chip fits beside the `+N`. A line with no measured width (not laid out yet, or a test
 * environment with no layout) shows every chip, and the cell's own overflow clips them.
 */
export function previewChipsThatFit(
  chipWidths: readonly number[],
  available: number,
  gap: number,
  moreWidth: number,
): number {
  const total = chipWidths.length
  if (!(available > 0)) return total
  const widthOf = (count: number) =>
    chipWidths.slice(0, count).reduce((sum, width) => sum + width, 0) + gap * Math.max(0, count - 1)
  // Every chip, with no `+N` to make room for.
  if (widthOf(total) <= available) return total
  // Otherwise the longest run that leaves room for the `+N` after it.
  for (let count = total - 1; count > 0; count--) {
    if (widthOf(count) + gap + moreWidth <= available) return count
  }
  return 0
}
