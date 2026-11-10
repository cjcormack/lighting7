// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

const uses = vi.hoisted(() => ({ keys: [] as string[] }))
vi.mock('@/store/surfaces', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/surfaces')>()),
  useSurfaceUses: (keys: string[]) => {
    uses.keys = keys
    return keys.includes('fixture:bar.2')
      ? [{ deviceTypeKey: 'xtc', deviceLabel: 'X-Touch Compact', controlId: 'fader-2', part: 'MAIN', label: 'Fader 2', does: 'Dimmer · Bar 2' }]
      : []
  },
  useSurfaceUsesProjectId: () => 7,
}))

import { SurfaceAssignRow } from './SurfaceAssignRow'
import { assignTargetNow, closeAssignToSurface } from './AssignToSurface'

/** The fixture and group sheets' door (surface-setup plan session 6). */
afterEach(() => {
  cleanup()
  closeAssignToSurface()
})

describe('the fixture sheet’s surface line', () => {
  it('assigns the fixture itself, and counts its heads’ bindings as its own', () => {
    render(
      <MemoryRouter>
        <SurfaceAssignRow name="Bar" subject={{ type: 'fixture', key: 'bar' }} also={[{ type: 'fixture', key: 'bar.2' }]} assignable />
      </MemoryRouter>,
    )
    expect(uses.keys).toEqual(['fixture:bar', 'fixture:bar.2'])
    expect(screen.getByText('Bound on Fader 2')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Assign to surface…' }))
    expect(assignTargetNow()).toEqual({ name: 'Bar', subjects: [{ type: 'fixture', key: 'bar' }] })
  })

  it('leaves the door off a card, keeping the chip', () => {
    render(
      <MemoryRouter>
        <SurfaceAssignRow name="Bar" subject={{ type: 'fixture', key: 'bar' }} also={[{ type: 'fixture', key: 'bar.2' }]} assignable={false} />
      </MemoryRouter>,
    )
    expect(screen.queryByRole('button', { name: 'Assign to surface…' })).toBeNull()
    expect(screen.getByText('Bound on Fader 2')).toBeInTheDocument()
  })
})
