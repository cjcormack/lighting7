// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * The busk view is the fourth live view, and that is nearly all this page is: the same header as
 * the other three, from the same hook, over a body of its own — and **no `ShowBar`** (busk-chrome
 * plan D1): the transport is the side sheet's Show tab's, fed from the one `useShowBarProps` call
 * this route makes and hands to the view.
 *
 * Two things it must **not** do are what these assert, because getting either wrong is invisible
 * until a desk is running: passing `canOperate` would gate GO on this page alone (busking is the
 * live use — the busk plan's D9), and binding transport keys would fire GO from a Space meant for
 * a focused pad (D5).
 */
const viewProps = vi.fn()
vi.mock('@/components/ShowHeader', () => ({
  ShowHeader: ({ view }: { view: string }) => <div data-testid="header">{view}</div>,
}))
const showBarMounted = vi.fn()
vi.mock('@/components/ShowBar', () => ({
  ShowBar: () => {
    showBarMounted()
    return <div data-testid="show-bar" />
  },
}))
vi.mock('@/components/busking/BuskingView', () => ({
  BuskingView: (props: Record<string, unknown>) => {
    viewProps(props)
    return <div data-testid="busking-view" />
  },
}))
const transportKeys = vi.fn()
vi.mock('@/hooks/useTransportKeys', () => ({
  useTransportKeys: (opts: unknown) => transportKeys(opts),
}))
const showBarOptions = vi.fn()
const barState = {
  showBarProps: { goDisabled: false, dbo: false, onDbo: vi.fn() },
  showHeaderProps: { isShowActive: true, canStart: false, onStart: vi.fn(), onStop: vi.fn() },
  transport: { go: vi.fn(), back: vi.fn(), serverActiveCueId: 12 },
  activeCue: null,
  standbyCue: null,
  nextStack: null,
}
vi.mock('@/hooks/useShowBarProps', () => ({
  useShowBarProps: (_projectId: number, opts: Record<string, unknown> = {}) => {
    showBarOptions(opts)
    return barState
  },
}))
vi.mock('../store/projects', () => ({
  useCurrentProjectQuery: () => ({ data: { id: 1 }, isLoading: false }),
  useProjectQuery: () => ({ data: { id: 1, name: 'Hamlet' }, isLoading: false }),
}))
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { ProjectBusk } from './Busk'

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

function draw(at = '/projects/1/busk') {
  return render(
    <MemoryRouter initialEntries={[at]}>
      <Routes>
        <Route path="/projects/:projectId/busk" element={<ProjectBusk />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ProjectBusk', () => {
  it('is a live view: one header naming itself, one body, and no bar on any board (busk-chrome D1)', () => {
    draw()
    expect(screen.getByTestId('header')).toHaveTextContent('busk')
    expect(screen.queryByTestId('show-bar')).toBeNull()
    expect(showBarMounted).not.toHaveBeenCalled()
    expect(screen.getByTestId('busking-view')).toBeInTheDocument()
  })

  it('hands the one transport to the view for its Show tab — the same object the hook answered', () => {
    draw()
    expect(viewProps).toHaveBeenCalledWith(expect.objectContaining({ projectId: 1, show: barState }))
  })

  it('does not gate the transport — GO works from a busk pad', () => {
    draw()
    expect(showBarOptions).toHaveBeenCalledWith(
      expect.not.objectContaining({ canOperate: expect.anything() }),
    )
  })

  it('binds no transport keys (D5) — Space on a focused pad must press the pad and nothing else', () => {
    draw()
    expect(transportKeys).not.toHaveBeenCalled()
  })

  /** The Show tab reads only `transport`'s cursors, and its cards read their own fade — so a running fade must not re-render the pad grid per frame. */
  it('opts out of frame-rate fade progress', () => {
    draw()
    expect(showBarOptions).toHaveBeenCalledWith(
      expect.objectContaining({ frameRateProgress: false }),
    )
  })
})
