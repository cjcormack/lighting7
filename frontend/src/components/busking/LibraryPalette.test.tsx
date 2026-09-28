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
  { id: 1, uuid: 't1', name: 'Amber Key', notes: null, sortOrder: 0, fadeDurationMs: null, groupId: null, family: 'COLOUR', isGeneric: true, kind: 'value', rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'colour', value: '#FF9D4A' }], effect: null, layerCount: 0 },
  { id: 2, uuid: 't2', name: 'Downstage Centre', notes: null, sortOrder: 1, fadeDurationMs: null, groupId: null, family: 'POSITION', isGeneric: true, kind: 'value', rows: [], effect: null, layerCount: 0 },
]

const looks = [
  { id: 5, uuid: 'l5', name: 'Ballyhoo', notes: null, sortOrder: 0, families: ['POSITION'], rowCount: 0, effectCount: 2, targetCount: 8, hasDeferredEffects: true, preview: [], layerCount: 0 },
  { id: 6, uuid: 'l6', name: 'Storm Wash', notes: null, sortOrder: 1, families: ['COLOUR'], rowCount: 3, effectCount: 0, targetCount: 10, hasDeferredEffects: false, preview: [], layerCount: 0 },
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
      { id: 20, name: 'Blackout', sortOrder: 0, layerCount: 0, adHocEffectCount: 0, autoAdvance: false, autoAdvanceDelayMs: null, fadeDurationMs: null, fadeCurve: 'LINEAR', cueNumber: '20', cueNumberAuto: false, notes: null, cueType: 'CUE' },
      { id: 21, name: 'Act 2', sortOrder: 1, layerCount: 0, adHocEffectCount: 0, autoAdvance: false, autoAdvanceDelayMs: null, fadeDurationMs: null, fadeCurve: 'LINEAR', cueNumber: null, cueNumberAuto: false, notes: null, cueType: 'MARKER' },
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

const rowNames = () =>
  Array.from(document.querySelectorAll('.min-h-10 .truncate:not(.text-\\[11px\\])')).map(
    (el) => el.textContent,
  )

describe('the library palette', () => {
  beforeEach(() => {
    installRelativeUrlRequest()
    installRecordingFetch({
      'projects/1/templates': templates,
      'projects/1/looks': looks,
      'projects/1/cue-stacks': stacks,
    })
  })

  afterEach(() => {
    cleanup()
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  it('lists templates, Looks and cues together', async () => {
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

  it('says which Looks are bound, because those are the ones a slot could take', async () => {
    draw()
    await screen.findByText('Storm Wash')
    expect(screen.getByText('10 fixtures · 3 values · bound')).toBeTruthy()
    expect(screen.getByText('8 fixtures · 2 effects')).toBeTruthy()
  })

  it('filters by kind', async () => {
    draw()
    await screen.findByText('Blackout')
    fireEvent.click(screen.getByRole('button', { name: 'Cues' }))
    expect(rowNames()).toEqual(['Blackout'])
  })

  it('filters by family, and a cue belongs to none', async () => {
    draw()
    await screen.findByText('Blackout')
    fireEvent.click(screen.getByRole('button', { name: 'Colour' }))
    expect(rowNames()).toEqual(['Amber Key', 'Storm Wash'])
  })

  it('searches names and cue numbers', async () => {
    draw()
    await screen.findByText('Blackout')
    fireEvent.change(screen.getByLabelText('Search the library'), { target: { value: 'amb' } })
    expect(rowNames()).toEqual(['Amber Key'])
    fireEvent.change(screen.getByLabelText('Search the library'), { target: { value: '20' } })
    expect(rowNames()).toEqual(['Blackout'])
  })

  it('marks exactly the records with a pad on the page being edited', async () => {
    draw(new Set(['look:6', 'cue:20']))
    await screen.findByText('Blackout')
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
 * A slot has no selection, so it holds only what needs none: a cue, or a Look bound to its own
 * fixtures (D7). `useCueSlotHover` is the signal — it crosses from the header, where the slots
 * live, into the palette, which is in the routed page — and is mocked here because jsdom cannot
 * drive a real drag.
 */
vi.mock('@/components/dnd/useCueSlotHover', () => ({ useCueSlotHover: () => true }))

describe('the palette while a slot is the drop target', () => {
  beforeEach(() => {
    installRelativeUrlRequest()
    installRecordingFetch({
      'projects/1/templates': templates,
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
    await screen.findByText('Storm Wash')

    const refusedIn = (name: string) =>
      screen.getByText(name).closest('.min-h-10')!.textContent!.includes('needs a selection')

    // A template needs a selection to land on; so does a Look with deferred effects.
    expect(refusedIn('Amber Key')).toBe(true)
    expect(refusedIn('Ballyhoo')).toBe(true)
    // A bound Look presses onto its own fixtures, and a cue has no targets at all.
    expect(refusedIn('Storm Wash')).toBe(false)
    expect(refusedIn('Blackout')).toBe(false)
  })
})
