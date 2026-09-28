import { afterEach, describe, expect, it } from 'vitest'
import { hasUnsavedSheets, resetUnsavedSheets, setSheetUnsaved, unsavedSheetCount } from './unsavedSheets'

/**
 * The dirty-sheet count `ui/sheet.tsx` feeds and a `windows.show` handler reads (multi-screen plan
 * §3.4). A count, not a flag: two sheets can be dirty at once, and the second closing must not
 * clear the first's claim.
 */
afterEach(resetUnsavedSheets)

describe('unsavedSheets', () => {
  it('counts each dirty sheet once, whatever it reports twice', () => {
    const a = Symbol('a')
    const b = Symbol('b')
    expect(hasUnsavedSheets()).toBe(false)
    setSheetUnsaved(a, true)
    setSheetUnsaved(a, true)
    expect(unsavedSheetCount()).toBe(1)
    setSheetUnsaved(b, true)
    expect(unsavedSheetCount()).toBe(2)
    expect(hasUnsavedSheets()).toBe(true)
  })

  it('keeps the first sheet’s claim when the second cleans up', () => {
    const a = Symbol('a')
    const b = Symbol('b')
    setSheetUnsaved(a, true)
    setSheetUnsaved(b, true)
    setSheetUnsaved(b, false)
    expect(hasUnsavedSheets()).toBe(true)
    setSheetUnsaved(a, false)
    expect(hasUnsavedSheets()).toBe(false)
  })

  it('ignores a clean report from a sheet that never claimed anything', () => {
    setSheetUnsaved(Symbol('never'), false)
    expect(unsavedSheetCount()).toBe(0)
  })
})
