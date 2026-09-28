// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

const patchLayer = vi.fn()
vi.mock('@/store/programmer', () => ({
  programmerPatchLayer: (...args: unknown[]) => patchLayer(...args),
}))

const lookStore = vi.hoisted(() => ({ current: null as unknown }))
vi.mock('./LookRowStore', () => ({ useLookRowStore: () => lookStore.current }))
const focusedTemplate = vi.hoisted(() => ({ current: null as unknown }))
vi.mock('./FocusedTemplateLayer', () => ({
  useFocusedTemplateLayer: () => focusedTemplate.current,
}))

import { AddToTargetsButton } from './AddToTargetsButton'

afterEach(() => {
  cleanup()
  patchLayer.mockClear()
  lookStore.current = null
  focusedTemplate.current = null
})

const props = {
  target: { type: 'fixture' as const, key: 'mover-1' },
  fixtureKeys: ['mover-1'],
  name: 'Mover 1',
}

describe('AddToTargetsButton', () => {
  it('widens a focused Look layer', () => {
    lookStore.current = {
      layerId: 3,
      targetedKeys: new Set(['hex-1']),
      targets: [{ type: 'fixture', key: 'hex-1' }],
    }
    render(<AddToTargetsButton {...props} />)
    fireEvent.click(screen.getByRole('button'))
    expect(patchLayer).toHaveBeenCalledWith(3, {
      targets: [
        { type: 'fixture', key: 'hex-1' },
        { type: 'fixture', key: 'mover-1' },
      ],
    })
  })

  it('widens a focused template layer too', () => {
    // Not optional. A template layer's grid is a read, and an untargeted row there is painted
    // dashed and non-editable — so without this the tone names a state with no way out of it, on a
    // surface whose only widening affordance is this button.
    focusedTemplate.current = {
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

  it('offers nothing outside layer scope, or on a row already targeted', () => {
    const { unmount } = render(<AddToTargetsButton {...props} />)
    expect(screen.queryByRole('button')).toBeNull()
    unmount()

    focusedTemplate.current = {
      layerId: 9,
      targetedKeys: new Set(['mover-1']),
      targets: [{ type: 'fixture', key: 'mover-1' }],
    }
    render(<AddToTargetsButton {...props} />)
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('offers nothing when the layer names no targets of its own', () => {
    // `targetedKeys === null` is "the source's own targets" — there is nothing to widen, and
    // reading it as "no targets" would put this button on every row in the grid.
    lookStore.current = { layerId: 3, targetedKeys: null, targets: [] }
    render(<AddToTargetsButton {...props} />)
    expect(screen.queryByRole('button')).toBeNull()
  })
})
