/**
 * An opt-in trace of the add-FX picker's closes (programmer-fixes plan D25): with
 * `localStorage['lighting7.debug.fxPickerClose'] = '1'` set in a window, each close logs one
 * `console.warn` naming the door that closed it. Read at every close, so setting it needs no
 * reload. Off, it logs nothing. It goes once the operator check in the plan's §6 settles why the
 * picker was seen closing mid-drag.
 */
export const FX_PICKER_CLOSE_FLAG = 'lighting7.debug.fxPickerClose'

function traceOn(): boolean {
  try {
    return globalThis.localStorage?.getItem(FX_PICKER_CLOSE_FLAG) === '1'
  } catch {
    return false
  }
}

export function traceFxPickerClose(door: string, detail: Record<string, unknown> = {}): void {
  if (!traceOn()) return
  console.warn(`[fx picker closed] ${door}`, detail)
}
