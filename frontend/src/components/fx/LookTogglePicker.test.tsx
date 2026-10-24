// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * The fixture sheet's Look picker is a press door with a pair of its own — the sheet's one target,
 * no mask — and it always writes Local, whatever the programmer's grid is pointed at.
 */
const toggleLook = vi.hoisted(() => vi.fn())
vi.mock('@/store/projects', () => ({ useCurrentProjectQuery: () => ({ data: { id: 1 } }) }))
vi.mock('@/store/looks', () => ({
  useLookListQuery: () => ({
    data: [{ id: 7, name: 'Warm', fxCount: 0 }],
    isLoading: false,
  }),
  useToggleLookMutation: () => [
    (args: unknown) => {
      toggleLook(args)
      return { unwrap: () => Promise.resolve({ action: 'applied', fxCount: 0, skippedFamilies: [] }) }
    },
  ],
  useApplyLookMutation: () => [vi.fn()],
  useAbsorbLookFxMutation: () => [vi.fn()],
}))
vi.mock('@/store/palettes', () => ({
  useApplyPaletteMutation: () => [vi.fn()],
  useTogglePaletteMutation: () => [vi.fn()],
}))
vi.mock('@/store/fixtureFx', () => ({ useRemoveFxMutation: () => [vi.fn()] }))
vi.mock('sonner', () => ({ toast: { error: vi.fn(), warning: vi.fn(), success: vi.fn() } }))
// A Look layer is focused on the grid behind the sheet: the picker must not write into it.
vi.mock('@/components/programmer/ProgrammerScope', () => ({ useProgrammerScope: () => ({ kind: 'layer', layerId: 4 }) }))
vi.mock('@/components/programmer/LookRowStore', () => ({
  useLookRowStore: () => ({ projectId: 1, layerId: 4, lookId: 9, lookName: 'Night', targetedKeys: null, setValue: vi.fn() }),
}))

const { LookTogglePicker } = await import('./LookTogglePicker')

afterEach(cleanup)

describe('LookTogglePicker', () => {
  it('presses the Look onto its own target with no mask, into Local', async () => {
    render(<LookTogglePicker targetType="fixture" targetKey="hex-2" compatibleLookIds={[7]} />)
    fireEvent.click(screen.getByTitle('Apply look'))
    await act(async () => {
      fireEvent.click(screen.getByText('Warm'))
    })
    expect(toggleLook).toHaveBeenCalledWith({
      projectId: 1,
      lookId: 7,
      targets: [{ type: 'fixture', key: 'hex-2' }],
      families: undefined,
    })
  })
})
