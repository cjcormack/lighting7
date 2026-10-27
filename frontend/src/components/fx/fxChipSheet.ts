import { createContext, useContext } from 'react'
import { useFingerSized } from '../fixtureSheet/sheetContext'

/**
 * Set by the FX editor below its 760 arm (FX editor plan §4.2's one column): the inspector's chips
 * open bottom sheets there rather than popovers (fx-settings plan §4), as they do on the phone host.
 */
export const FxChipSheetContext = createContext(false)

/** Whether an `FxFields` chip opens a bottom sheet with finger-sized rows. */
export function useFxChipSheet(): boolean {
  const narrow = useContext(FxChipSheetContext)
  const finger = useFingerSized()
  return narrow || finger
}
