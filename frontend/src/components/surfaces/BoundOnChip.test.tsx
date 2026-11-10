// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SurfaceUsesFrame } from '@/store/surfaces'
import { USES_FRAME } from './surfaceFixtures'

const socket = vi.hoisted(() => ({
  frame: null as SurfaceUsesFrame | null,
  listeners: new Set<(frame: SurfaceUsesFrame) => void>(),
  push(frame: SurfaceUsesFrame) {
    this.frame = frame
    this.listeners.forEach((fn) => fn(frame))
  },
}))

vi.mock('@/api/lightingApi', () => {
  const noSubscription = { unsubscribe: () => {} }
  const anyNamespace = new Proxy({}, { get: () => () => noSubscription })
  const surfaces = new Proxy(
    {},
    {
      get: (_target, name: string) => {
        if (name === 'getUses') return () => socket.frame
        if (name === 'subscribeUses') {
          return (fn: (frame: SurfaceUsesFrame) => void) => {
            socket.listeners.add(fn)
            return { unsubscribe: () => socket.listeners.delete(fn) }
          }
        }
        return () => noSubscription
      },
    },
  )
  return { lightingApi: new Proxy({}, { get: (_target, name) => (name === 'surfaces' ? surfaces : anyNamespace) }) }
})

import { BoundOnChip, boundOnText } from './BoundOnChip'

/** *Bound on* (surface-setup plan session 6): the desk's index, drawn on the record, live. */

const STACK = { type: 'record', kind: 'STACK', uuid: '11111111-1111-4111-8111-111111111111' } as const

function Where() {
  const location = useLocation()
  return <div data-testid="where">{location.pathname + location.search}</div>
}

function draw(projectId?: number) {
  render(
    <MemoryRouter>
      <Routes>
        <Route path="*" element={<><BoundOnChip subjects={[STACK]} projectId={projectId} /><Where /></>} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  socket.frame = null
  socket.listeners.clear()
})
afterEach(cleanup)

describe('Bound on', () => {
  it('says where, and counts the rest', () => {
    const uses = USES_FRAME.uses['group:front-wash']
    expect(boundOnText(uses)).toBe(`Bound on Fader 1 · A +${uses.length - 1}`)
    expect(boundOnText([])).toBeNull()
  })

  it('draws nothing until something is bound, then updates when an assign elsewhere moves the index', () => {
    draw()
    expect(screen.queryByText(/Bound on/)).toBeNull()
    act(() => socket.push(USES_FRAME))
    expect(screen.getByText('Bound on Button 3')).toBeInTheDocument()
    act(() =>
      socket.push({
        ...USES_FRAME,
        uses: {
          ...USES_FRAME.uses,
          [`record:STACK:${STACK.uuid}`]: [
            ...USES_FRAME.uses[`record:STACK:${STACK.uuid}`],
            { deviceTypeKey: 'x-touch-compact-standard', deviceLabel: 'X-Touch Compact', controlId: 'btn-36', part: 'MAIN', layer: 'layer-a', label: 'Button 36 · A', does: 'GO · Act 1' },
          ],
        },
      }),
    )
    expect(screen.getByText('Bound on Button 3 +1')).toBeInTheDocument()
  })

  it('opens the Surfaces page on the control', () => {
    socket.frame = USES_FRAME
    draw()
    fireEvent.click(screen.getByText('Bound on Button 3'))
    expect(screen.getByTestId('where')).toHaveTextContent('/projects/7/settings/surfaces?type=x-touch-compact-standard&control=btn-3')
  })

  it('names nothing for a record of a show that is not the one the index is for', () => {
    socket.frame = USES_FRAME
    draw(3)
    expect(screen.queryByText(/Bound on/)).toBeNull()
  })
})
