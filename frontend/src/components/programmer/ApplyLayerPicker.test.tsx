// @vitest-environment jsdom
import { cleanup, render } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * The Stack table's *+ Look or palette*: the one picker on the window's selection (apply-model plan
 * §4.6), so a press from the table presses what row C's strip would. What the picker does with a
 * press is `ApplyPicker.test.tsx`'s; here it is the props it is opened with.
 */
const pickerProps = vi.hoisted(() => ({ last: null as null | Record<string, unknown> }))
vi.mock('@/components/press/ApplyPicker', () => ({
  ApplyPicker: (props: Record<string, unknown>) => {
    pickerProps.last = props
    return null
  },
}))
const WINDOW_PAIR = vi.hoisted(() => ({ targets: [{ type: 'fixture', key: 'hex-1' }], families: ['COLOUR'] }))
const WINDOW_FIT = vi.hoisted(() => ({ families: ['COLOUR'], emitters: [] }))
vi.mock('@/hooks/usePress', () => ({
  useWindowPressPair: () => WINDOW_PAIR,
  useWindowPaletteFit: () => WINDOW_FIT,
}))

import { ApplyLayerPicker } from './ApplyLayerPicker'

afterEach(cleanup)

describe('ApplyLayerPicker', () => {
  it('opens the one picker on the window’s pair, for the library asked', () => {
    render(<ApplyLayerPicker projectId={6} library="palettes" onClose={() => {}} />)
    expect(pickerProps.last).toMatchObject({ open: true, library: 'palettes', pair: WINDOW_PAIR, fit: WINDOW_FIT, saveFamilies: null })
    render(<ApplyLayerPicker projectId={6} library="looks" onClose={() => {}} />)
    expect(pickerProps.last).toMatchObject({ open: true, library: 'looks', pair: WINDOW_PAIR })
  })
})
