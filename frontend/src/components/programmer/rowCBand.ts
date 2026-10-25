import { createContext, useContext } from 'react'

/**
 * Which rung of the programmer's row C the row is on, by its own measured width (plan D12):
 * `desk` from 1100 (every word, ⏎ and ⌫ on Set and Release), `lap` from 800 (Locate and Highlight
 * iconic), `tab` from 600 (every control iconic, the mask abbreviated), `phone` below (Locate and
 * Highlight in the heads chip's menu, Release ▾ a bottom sheet).
 *
 * Measured rather than container-queried because two of its answers are structural: a menu item
 * renders in a portal outside the row's `@container`, so no query could put Locate into the heads
 * chip's menu at exactly the width the buttons leave the row.
 */
export type RowCBand = 'desk' | 'lap' | 'tab' | 'phone'

export const ROW_C_FLOORS: readonly number[] = [1100, 800, 600, 0]
export const ROW_C_BANDS: readonly RowCBand[] = ['desk', 'lap', 'tab', 'phone']

export const RowCBandContext = createContext<RowCBand>('desk')

export function useRowCBand(): RowCBand {
  return useContext(RowCBandContext)
}
