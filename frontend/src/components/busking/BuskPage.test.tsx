// @vitest-environment jsdom
import { Provider } from 'react-redux'
import { DndContext } from '@dnd-kit/core'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { installRelativeUrlRequest } from '@/test/backendMock'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())
vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

import { store } from '@/store'
import { buskApi, resetBuskCommitState } from '@/store/busk'
import { restApi } from '@/store/restApi'
import type { BuskPad, BuskPage } from '@/api/buskApi'
import { BuskEditContext, BuskEditProvider } from './BuskEditProvider'
import { BuskPageBody, EDIT_COLUMN_MIN_PX, STACK_BELOW_PX } from './BuskPage'
import type { PadBehaviour } from './padBehaviour'
import type { BuskDragData } from './buskDnd'

/** Rows of columns of banks, from a document — and the width shares reaching the grid. */

let seq = 0
function pad(name: string, kind: BuskPad['kind'] = 'TEMPLATE'): BuskPad {
  const id = ++seq
  if (kind === 'CUE') {
    return {
      id,
      uuid: `p${id}`,
      kind,
      cue: { id, uuid: `c${id}`, name, cueNumber: String(id), cueStackId: 1, cueStackName: 'Main' },
    }
  }
  if (kind === 'LOOK') {
    return { id, uuid: `p${id}`, kind, look: { id, name, rowCount: 2, effectCount: 1 } as never }
  }
  return { id, uuid: `p${id}`, kind, template: { id, name, rows: [], isGeneric: true } as never }
}

const page: BuskPage = {
  id: 1,
  uuid: 'page-1',
  name: 'Ballads',
  sortOrder: 0,
  rows: [
    {
      columns: [
        {
          id: 1,
          uuid: 'c1',
          width: 6,
          banks: [
            {
              id: 1,
              uuid: 'b1',
              name: 'Movement',
              solo: true,
              flow: 'WRAP',
              pads: [pad('Ballyhoo'), pad('Storm Wash', 'LOOK'), pad('Verse 2', 'CUE')],
            },
          ],
        },
        {
          id: 2,
          uuid: 'c2',
          width: 3,
          banks: [
            { id: 2, uuid: 'b2', name: 'Colour', solo: false, flow: 'COLUMN', pads: [pad('Amber')] },
            { id: 3, uuid: 'b3', name: 'Beam', solo: false, flow: 'WRAP', pads: [] },
          ],
        },
      ],
    },
    {
      columns: [
        {
          id: 3,
          uuid: 'c3',
          width: 12,
          banks: [{ id: 4, uuid: 'b4', name: 'Texture', solo: false, flow: 'SCROLL', pads: [pad('Disco')] }],
        },
      ],
    },
  ],
}

const behaviour: PadBehaviour = {
  presenceOf: () => 'none',
  isLive: () => false,
  onPress: () => {},
  onInspect: () => {},
  onPickUp: () => {},
  onHandPlace: () => {},
}

function draw(editing = false) {
  return render(
    <Provider store={store}>
      <DndContext>
        <BuskEditProvider editing={editing} projectId={1} page={page}>
          <BuskPageBody page={page} behaviour={behaviour} />
        </BuskEditProvider>
      </DndContext>
    </Provider>,
  )
}

afterEach(cleanup)

describe('a page', () => {
  it('draws every bank in every column of every row', () => {
    draw()
    for (const name of ['Movement', 'Colour', 'Beam', 'Texture']) {
      expect(screen.getByText(name)).toBeTruthy()
    }
    expect(screen.getAllByTestId(/busk-row-/)).toHaveLength(2)
  })

  it('turns the width shares into grid tracks', () => {
    draw()
    const row = screen.getByTestId('busk-row-0')
    expect(row.style.getPropertyValue('--busk-tracks')).toBe('6fr 3fr')
  })

  it('stacks the columns below the body width the docblock argues, by container query', () => {
    // jsdom evaluates no container query, so this pins the mechanism: the tracks reach the grid
    // through a variable a class can override, and the override is keyed on the body's width.
    const { container } = draw()
    expect(container.firstElementChild!.className).toContain('@container')
    const row = screen.getByTestId('busk-row-0')
    expect(row.className).toContain('[grid-template-columns:var(--busk-tracks)]')
    expect(row.className).toContain(`@max-[${STACK_BELOW_PX}px]:grid-cols-1`)
  })

  it('holds all three pad kinds in one bank', () => {
    draw()
    expect(screen.getByText('Ballyhoo')).toBeTruthy()
    expect(screen.getByText('Storm Wash')).toBeTruthy()
    expect(screen.getByText('Verse 2')).toBeTruthy()
  })

  it('marks a solo bank and leaves a stacking one unmarked', () => {
    draw()
    expect(screen.getAllByText('solo')).toHaveLength(1)
  })

  it('lays a COLUMN bank out one pad per line, a WRAP bank as a grid, and a SCROLL bank as one sideways line of fixed-width pads', () => {
    const { container } = draw()
    const colBank = screen.getByText('Colour').closest('div')!.parentElement!
    expect(within(colBank).getByText('Amber')).toBeTruthy()
    expect(colBank.querySelector('.grid-cols-1')).toBeTruthy()
    expect(container.querySelector('.grid-cols-\\[repeat\\(auto-fill\\,minmax\\(110px\\,1fr\\)\\)\\]')).toBeTruthy()
    // SCROLL — the rig row's own flow, offered to banks too.
    const scroll = container.querySelector('[data-bank-flow="SCROLL"]')!
    expect(scroll.className).toContain('overflow-x-auto')
    expect(scroll.className).toContain('*:shrink-0')
    expect(within(scroll as HTMLElement).getByText('Disco')).toBeTruthy()
  })

  it('renders an empty bank rather than dropping it', () => {
    draw()
    expect(screen.getByText('Beam')).toBeTruthy()
  })

  it('offers no edit affordances until the mode is on', () => {
    draw()
    expect(screen.queryByText('Row')).toBeNull()
    expect(screen.queryByLabelText('Bank name')).toBeNull()
  })

  it('grows the row, bank and name affordances in edit mode', () => {
    draw(true)
    expect(screen.getByText('Row')).toBeTruthy()
    expect(screen.getAllByText('Bank').length).toBeGreaterThan(0)
    expect(screen.getAllByLabelText('Bank name')).toHaveLength(4)
    // The gutters are in the DOM for the whole of edit mode, not only during a drag — and every
    // editing column has a floor under its share, so a bank header's controls cannot run past the
    // bank's border in a quarter-width column (`EDIT_COLUMN_MIN_PX`).
    expect(screen.getByTestId('busk-row-0').style.getPropertyValue('--busk-tracks')).toBe(
      `20px minmax(${EDIT_COLUMN_MIN_PX}px, 6fr) 20px minmax(${EDIT_COLUMN_MIN_PX}px, 3fr) 20px 3fr`,
    )
  })
})

describe('the drop slot', () => {
  function drawWith(source: BuskDragData | null) {
    const target = { kind: 'pad' as const, at: { row: 0, column: 0, bank: 0, pad: 1 } }
    return render(
      <Provider store={store}>
        <DndContext>
          <BuskEditContext.Provider value={{ editing: true, source, target, foreign: false, commit: () => {} }}>
            <BuskPageBody page={page} behaviour={behaviour} />
          </BuskEditContext.Provider>
        </DndContext>
      </Provider>,
    )
  }

  it('opens where a lifted pad would land', () => {
    const { container } = drawWith({
      type: 'busk-pad',
      at: { row: 1, column: 0, bank: 0, pad: 0 },
      face: { kind: 'TEMPLATE', name: 'Disco' } as never,
    })
    expect(container.querySelector('.border-dashed.border-primary')).toBeTruthy()
  })

  it('never opens for a lifted bank, which lands on the bank zones instead', () => {
    // A bank over a pad used to open a slot there and then drop nowhere.
    const { container } = drawWith({
      type: 'busk-bank',
      at: { row: 1, column: 0, bank: 0 },
      name: 'Texture',
      padCount: 1,
    })
    expect(container.querySelector('.border-dashed.border-primary')).toBeNull()
    expect(screen.getAllByText('stack under').length).toBeGreaterThan(0)
  })
})

describe('renaming a bank', () => {
  beforeEach(() => {
    installRelativeUrlRequest()
    resetBuskCommitState()
  })

  afterEach(() => {
    resetBuskCommitState()
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  it('writes once when the field is left, not once per keystroke', async () => {
    // Every gesture saves the *whole page*, so a per-keystroke commit would be one full layout PUT
    // and one broadcast per character.
    const writes: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const request = input as Request
        writes.push(request.method)
        return new Response(JSON.stringify(page), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }),
    )
    await store.dispatch(buskApi.util.upsertQueryData('buskPages', 1, [page]))

    draw(true)
    const field = screen.getAllByLabelText('Bank name')[0]
    fireEvent.change(field, { target: { value: 'Mover' } })
    fireEvent.change(field, { target: { value: 'Movers' } })
    expect(writes).toHaveLength(0)
    expect((field as HTMLInputElement).value).toBe('Movers')

    fireEvent.blur(field)
    await waitFor(() => expect(writes.filter((m) => m === 'PUT')).toHaveLength(1))
  })

  it('puts the stored name back when left blank, and writes nothing', async () => {
    // The server refuses a blank name; the field reverting is the better answer than its toast.
    const writes: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        writes.push('PUT')
        return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } })
      }),
    )
    await store.dispatch(buskApi.util.upsertQueryData('buskPages', 1, [page]))

    draw(true)
    const field = screen.getAllByLabelText('Bank name')[0] as HTMLInputElement
    fireEvent.change(field, { target: { value: '   ' } })
    fireEvent.blur(field)
    expect(field.value).toBe('Movement')
    expect(writes).toHaveLength(0)
  })

  it('puts the stored name back on Escape and writes nothing', async () => {
    const writes: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        writes.push('PUT')
        return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } })
      }),
    )
    await store.dispatch(buskApi.util.upsertQueryData('buskPages', 1, [page]))

    draw(true)
    const field = screen.getAllByLabelText('Bank name')[0] as HTMLInputElement
    fireEvent.change(field, { target: { value: 'Nope' } })
    fireEvent.keyDown(field, { key: 'Escape' })
    expect(field.value).toBe('Movement')
    expect(writes).toHaveLength(0)
  })
})
