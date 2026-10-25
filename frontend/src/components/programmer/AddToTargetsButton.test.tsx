// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

const patchLayer = vi.fn()
vi.mock('@/store/programmer', () => ({
  programmerPatchLayer: (...args: unknown[]) => patchLayer(...args),
}))

const focusedPalette = vi.hoisted(() => ({ current: null as unknown }))
vi.mock('./FocusedPaletteLayer', () => ({
  useFocusedPaletteLayer: () => focusedPalette.current,
}))

import { AddToTargetsButton } from './AddToTargetsButton'

afterEach(() => {
  cleanup()
  patchLayer.mockClear()
  focusedPalette.current = null
})

const props = {
  target: { type: 'fixture' as const, key: 'mover-1' },
  fixtureKeys: ['mover-1'],
  name: 'Mover 1',
}

describe('AddToTargetsButton', () => {
  it('widens a focused palette layer', () => {
    // A palette layer's grid is a read, and an untargeted row there is painted dashed and
    // non-editable — so without this the tone names a state with no way out of it.
    focusedPalette.current = {
      layerId: 9,
      targetedKeys: new Set(['hex-1']),
      targets: [{ type: 'fixture', key: 'hex-1' }],
    }
    render(<AddToTargetsButton {...props} />)
    fireEvent.click(screen.getByRole('button'))
    expect(patchLayer).toHaveBeenCalledWith(9, {
      targets: [
        { type: 'fixture', key: 'hex-1' },
        { type: 'fixture', key: 'mover-1' },
      ],
    })
  })

  it('offers nothing outside a palette layer — a Look layer names no targets to widen', () => {
    const { unmount } = render(<AddToTargetsButton {...props} />)
    expect(screen.queryByRole('button')).toBeNull()
    unmount()
  })

  it('offers nothing on a row already targeted', () => {
    focusedPalette.current = {
      layerId: 9,
      targetedKeys: new Set(['mover-1']),
      targets: [{ type: 'fixture', key: 'mover-1' }],
    }
    render(<AddToTargetsButton {...props} />)
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('offers nothing when the palette layer names no targets of its own', () => {
    // `targetedKeys === null` is a per-fixture palette's own heads — there is nothing to widen.
    focusedPalette.current = { layerId: 9, targetedKeys: null, targets: [] }
    render(<AddToTargetsButton {...props} />)
    expect(screen.queryByRole('button')).toBeNull()
  })
})
