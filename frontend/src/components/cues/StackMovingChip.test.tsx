// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { MoveInDarkHead } from '@/api/moveInDarkApi'

/**
 * The stack row's and the phone runner's Move in dark chip (move-in-dark plan session 4, design
 * §5.4): what a stack moves now, nothing while nothing moves, and lit early in amber beside it.
 */
const world: { heads: MoveInDarkHead[] } = { heads: [] }
vi.mock('@/store/moveInDark', () => ({ useMoveInDarkHeads: () => world.heads }))

import { StackMovingChip } from './StackMovingChip'

const head = (over: Partial<MoveInDarkHead>): MoveInDarkHead => ({
  target: 'spot-1',
  entryId: 112,
  cueId: 12,
  label: 'Q12',
  state: 'MOVING',
  endsAtMs: null,
  families: ['POSITION'],
  position: null,
  ...over,
})

afterEach(() => {
  cleanup()
  world.heads = []
})

describe('StackMovingChip', () => {
  it('draws nothing while the stack moves nothing — every stopped stack', () => {
    const { container } = render(<StackMovingChip stackId={7} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('the stack row counts the heads moving, and lit early apart', () => {
    world.heads = [head({}), head({ target: 'spot-2', state: 'SET' }), head({ target: 'spot-3', state: 'LIT_EARLY' })]
    render(<StackMovingChip stackId={7} />)
    expect(screen.getByText('2 moving')).toBeInTheDocument()
    expect(screen.getByText('1 lit early')).toBeInTheDocument()
  })

  it('the stack row names no cue and no time, which it does not count down', () => {
    world.heads = [head({ endsAtMs: Date.now() + 1400 })]
    render(<StackMovingChip stackId={7} />)
    expect(screen.getByText('1 moving')).toBeInTheDocument()
  })

  it('the phone’s Up next names the cue the heads move for and the time left', () => {
    world.heads = [head({ endsAtMs: Date.now() + 1400 })]
    render(<StackMovingChip stackId={7} detail />)
    expect(screen.getByText(/^1 moving for Q12 · 1\.[34]s$/)).toBeInTheDocument()
  })
})
