// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * Record ▾'s *A new palette*: *Use it on these heads* (palette-references plan D15, call C) is on by
 * default and sends `asReference`, so the recorded values become references to the new palette.
 */
const mocks = vi.hoisted(() => ({
  record: vi.fn(),
  placeAfterCreate: vi.fn(async () => {}),
  // Stable, as RTK Query's is: the sheet clears itself on a change of `reset`.
  reset: () => {},
}))

vi.mock('@/store/palettes', () => ({
  useCreatePaletteFromProgrammerMutation: () => [mocks.record, { isLoading: false, error: undefined, reset: mocks.reset }],
}))
vi.mock('@/components/busking/AlsoAddToBuskRow', () => ({ AlsoAddToBuskRow: () => null }))
vi.mock('@/components/busking/useAddToBuskPage', () => ({ useAddToBuskPage: () => ({ placeAfterCreate: mocks.placeAfterCreate }) }))

import { NewPaletteFromSelectionSheet } from './NewPaletteFromSelectionSheet'

const TARGETS = ['spot-1', 'spot-2', 'spot-3', 'spot-4'].map((key) => ({ type: 'fixture' as const, key }))

function open() {
  render(
    <NewPaletteFromSelectionSheet open onOpenChange={() => {}} projectId={1} families={['POSITION']} targets={TARGETS} />,
  )
  fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'DSR Focus' } })
}

beforeEach(() => {
  mocks.record.mockReset().mockReturnValue({
    unwrap: async () => ({ palette: { id: 4, name: 'DSR Focus', rows: [{}, {}, {}, {}] }, isGeneric: false, skipped: [], referenced: 4 }),
  })
  mocks.placeAfterCreate.mockClear()
})
afterEach(cleanup)

describe('NewPaletteFromSelectionSheet — Use it on these heads', () => {
  it('is on by default, says what it does, and sends asReference', async () => {
    open()
    const box = screen.getByRole('checkbox', { name: /Use it on these heads/ })
    expect(box).toBeChecked()
    expect(screen.getByText(/The 4 selected heads’ position values become references to DSR Focus\. Nothing moves on stage/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Create palette' }))
    await waitFor(() => expect(mocks.record).toHaveBeenCalled())
    expect(mocks.record.mock.calls[0][0]).toMatchObject({ name: 'DSR Focus', mask: ['POSITION'], asReference: true })
    await waitFor(() => expect(mocks.placeAfterCreate).toHaveBeenCalled())
    expect(mocks.placeAfterCreate.mock.calls[0]).toContain(
      '“DSR Focus” — per fixture, 4 heads · 4 values are references to it now; nothing moved on stage',
    )
  })

  it('sends asReference false once unticked, and says nothing of references', async () => {
    mocks.record.mockReturnValue({
      unwrap: async () => ({ palette: { id: 4, name: 'DSR Focus', rows: [{}] }, isGeneric: true, skipped: [], referenced: 0 }),
    })
    open()
    fireEvent.click(screen.getByRole('checkbox', { name: /Use it on these heads/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Create palette' }))
    await waitFor(() => expect(mocks.record).toHaveBeenCalled())
    expect(mocks.record.mock.calls[0][0]).toMatchObject({ asReference: false })
    await waitFor(() => expect(mocks.placeAfterCreate).toHaveBeenCalled())
    expect(mocks.placeAfterCreate.mock.calls[0]).toContain('“DSR Focus” — one value for any head')
  })

  it('keeps an unticked box when the host hands over a fresh families array while it is open', async () => {
    const { rerender } = render(
      <NewPaletteFromSelectionSheet open onOpenChange={() => {}} projectId={1} families={['POSITION']} targets={TARGETS} />,
    )
    fireEvent.click(screen.getByRole('checkbox', { name: /Use it on these heads/ }))
    rerender(<NewPaletteFromSelectionSheet open onOpenChange={() => {}} projectId={1} families={['POSITION']} targets={TARGETS} />)
    expect(screen.getByRole('checkbox', { name: /Use it on these heads/ })).not.toBeChecked()
  })

  it('says the selection’s values where it names a group', () => {
    render(
      <NewPaletteFromSelectionSheet
        open
        onOpenChange={() => {}}
        projectId={1}
        families={['POSITION']}
        targets={[{ type: 'group', key: 'front' }]}
      />,
    )
    expect(screen.getByText(/^The selection’s position values become references to it\./)).toBeTruthy()
  })
})
