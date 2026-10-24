/**
 * The Order's words — the Order (effects plan §3.1). One
 * vocabulary for every surface that names a strategy: the FX editor's Order, a Look's or cue's FX
 * summary (`getOrderLabel`), a group's *Runs as* picker (`ORDER_OPTION_ENTRIES`)
 * and the Fan's order row (`SPREAD_ORDERS`).
 *
 * It imports nothing, on purpose: `lib/spreadIntent.ts` reads it and must reach no client walk
 * (`spreadIntent.test.ts`), and `lib/spreadShape.ts` re-exports it.
 */

export const ORDER_LABELS: Record<string, string> = {
  NORMAL: 'Normal',
  REVERSE: 'Reverse',
  CENTRE_OUT: 'Centre out',
  CENTRE_IN: 'Centre in',
  RANDOM: 'Random',
  PING_PONG: 'Ping-pong',
  POSITIONAL: 'Position',
  UNIFIED: 'Unified',
}

/** Every Order in the one order every select offers them in. */
export const ORDER_OPTIONS: readonly string[] = [
  'NORMAL',
  'REVERSE',
  'CENTRE_OUT',
  'CENTRE_IN',
  'RANDOM',
  'PING_PONG',
  'POSITIONAL',
  'UNIFIED',
]

/** [order]'s words, or the stored name itself for an Order this vocabulary does not know. */
export function orderLabel(order: string): string {
  return ORDER_LABELS[order] ?? order
}
