// @vitest-environment jsdom
import { Provider } from 'react-redux'
import { DndContext } from '@dnd-kit/core'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, configure, fireEvent, render, screen } from '@testing-library/react'
import { installRecordingFetch, installRelativeUrlRequest } from '@/test/backendMock'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

// Everything here waits on a REST round trip through the mock's 1ms timer. The default 1s is
// tight when the whole suite runs in parallel and the event loop is saturated — these tests
// went flaky at it, and a longer ceiling costs nothing when they pass.
configure({ asyncUtilTimeout: 5000 })

import { store } from '@/store'
import { restApi } from '@/store/restApi'
import { LibraryPalette } from './LibraryPalette'

/** The library as a palette: one search, two filters, and a row per placeable record. */

const templates = [
  { id: 1, uuid: 't1', name: 'Amber Key', notes: null, sortOrder: 0, fadeDurationMs: null, groupId: null, family: 'COLOUR', isGeneric: true, kind: 'value', rows: [{ targetType: 'deferred', targetKey: '', attribute: 'colour', value: '#FF9D4A' }], fx: null, layerCount: 0 },
  { id: 2, uuid: 't2', name: 'Downstage Centre', notes: null, sortOrder: 1, fadeDurationMs: null, groupId: null, family: 'POSITION', isGeneric: true, kind: 'value', rows: [], fx: null, layerCount: 0 },
]

const looks = [
  { id: 5, uuid: 'l5', name: 'Ballyhoo', notes: null, sortOrder: 0, families: ['POSITION'], rowCount: 0, fxCount: 2, targetCount: 8, preview: [], layerCount: 0 },
  { id: 6, uuid: 'l6', name: 'Storm Wash', notes: null, sortOrder: 1, families: ['COLOUR'], rowCount: 3, fxCount: 0, targetCount: 10, preview: [], layerCount: 0 },
]

const stacks = [
  {
    id: 3,
    name: 'Main Show',
    loop: false,
    sortOrder: 0,
    type: 'STACK',
    label: null,
    activeCueId: null,
    nextCueId: null,
    canEdit: true,
    canDelete: true,
    cues: [
      { id: 20, name: 'Blackout', sortOrder: 0, layerCount: 0, adHocFxCount: 0, autoAdvance: false, autoAdvanceDelayMs: null, fadeDurationMs: null, fadeCurve: 'LINEAR', cueNumber: '20', cueNumberAuto: false, notes: null, cueType: 'CUE' },
      { id: 21, name: 'Act 2', sortOrder: 1, layerCount: 0, adHocFxCount: 0, autoAdvance: false, autoAdvanceDelayMs: null, fadeDurationMs: null, fadeCurve: 'LINEAR', cueNumber: null, cueNumberAuto: false, notes: null, cueType: 'MARKER' },
    ],
  },
]

function draw(onPage = new Set<string>()) {
  return render(
    <Provider store={store}>
      <DndContext>
        <LibraryPalette projectId={1} onPageKeys={onPage} onRigKeys={new Set()} />
      </DndContext>
    </Provider>,
  )
}

/**
 * One row from each of the three requests the palette makes. They settle in no guaranteed order,
 * so a test that reads rows from more than one of them waits for all three.
 */
async function loaded() {
  await screen.findByText('Amber Key')
  await screen.findByText('Storm Wash')
  await screen.findByText('Blackout')
}

const rowNames = () =>
  Array.from(document.querySelectorAll('.min-h-10 .truncate:not(.text-\\[11px\\])')).map(
    (el) => el.textContent,
  )

describe('the library palette', () => {
  beforeEach(() => {
    installRelativeUrlRequest()
    installRecordingFetch({
      'projects/1/palettes': templates,
      'projects/1/looks': looks,
      'projects/1/cue-stacks': stacks,
    })
  })

  afterEach(() => {
    cleanup()
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  it('lists palettes, Looks and cues together', async () => {
    draw()
    // Three separate queries, which settle in no guaranteed order — await each.
    expect(await screen.findByText('Amber Key')).toBeTruthy()
    expect(await screen.findByText('Ballyhoo')).toBeTruthy()
    expect(await screen.findByText('Blackout')).toBeTruthy()
  })

  it('drops a MARKER, which cannot be fired and so cannot be a pad', async () => {
    draw()
    await screen.findByText('Blackout')
    expect(screen.queryByText('Act 2')).toBeNull()
  })

  it('describes a Look by what it holds', async () => {
    draw()
    await screen.findByText('Storm Wash')
    expect(screen.getByText('10 fixtures · 3 values')).toBeTruthy()
    expect(screen.getByText('8 fixtures · 2 FX')).toBeTruthy()
  })

  it('filters by kind', async () => {
    draw()
    await screen.findByText('Blackout')
    fireEvent.click(screen.getByRole('button', { name: 'Cues' }))
    expect(rowNames()).toEqual(['Blackout'])
  })

  it('filters by family, and a cue belongs to none', async () => {
    draw()
    await loaded()
    fireEvent.click(screen.getByRole('button', { name: 'Colour' }))
    expect(rowNames()).toEqual(['Amber Key', 'Storm Wash'])
  })

  it('searches names and cue numbers', async () => {
    draw()
    await loaded()
    fireEvent.change(screen.getByLabelText('Search the library'), { target: { value: 'amb' } })
    expect(rowNames()).toEqual(['Amber Key'])
    fireEvent.change(screen.getByLabelText('Search the library'), { target: { value: '20' } })
    expect(rowNames()).toEqual(['Blackout'])
  })

  it('marks exactly the records with a pad on the page being edited', async () => {
    draw(new Set(['look:6', 'cue:20']))
    await loaded()
    expect(screen.getAllByText('on page')).toHaveLength(2)
  })

  it('puts the drag listeners on the grip and not on the row', async () => {
    draw()
    await screen.findByText('Amber Key')
    // The app's pointer sensor activates at 8px and is shared, so a row that dragged by its body
    // would swallow every attempt to scroll this list on a touchscreen.
    const grip = screen.getByRole('button', { name: 'Place Amber Key' })
    expect(grip.className).toContain('touch-none')
    expect(grip.closest('.min-h-10')!.getAttribute('role')).toBeNull()
  })
})

/**
 * While a drag is over an FX cue slot, the rows a slot cannot take say why.
 *
 * A slot has no selection, so it holds only what needs none: a cue, or a Look, which lands whole
 * (D7; absolute-looks plan D1). `useCueSlotHover` is the signal — it crosses from the header, where the slots
 * live, into the palette, which is in the routed page — and is mocked here because jsdom cannot
 * drive a real drag.
 */
vi.mock('@/components/dnd/useCueSlotHover', () => ({ useCueSlotHover: () => true }))

describe('the palette while a slot is the drop target', () => {
  beforeEach(() => {
    installRelativeUrlRequest()
    installRecordingFetch({
      'projects/1/palettes': templates,
      'projects/1/looks': looks,
      'projects/1/cue-stacks': stacks,
    })
  })

  afterEach(() => {
    cleanup()
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  it('marks the rows a slot cannot take, and leaves the rest alone', async () => {
    draw()
    await loaded()

    const refusedIn = (name: string) =>
      screen.getByText(name).closest('.min-h-10')!.textContent!.includes('needs a selection')

    // A palette needs a selection to land on.
    expect(refusedIn('Amber Key')).toBe(true)
    // A Look lands whole, and a cue has no targets at all.
    expect(refusedIn('Ballyhoo')).toBe(false)
    expect(refusedIn('Storm Wash')).toBe(false)
    expect(refusedIn('Blackout')).toBe(false)
  })
})
