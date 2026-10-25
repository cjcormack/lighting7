/** `1 head`, `6 heads`: a count and its noun, with a plain `s` for the plural. */
export function plural(n: number, noun: string): string {
  return `${n} ${noun}${n === 1 ? '' : 's'}`
}
