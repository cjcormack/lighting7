// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import type { Cue } from '@/api/cuesApi'
import type { CueStackCueRow } from '@/api/cueStacksApi'

/**
 * The cue editor (show-cue-editing plan §4.1): the header's two halves — the entry's number and
 * follow written to the entry by its id, the name, fade and curve to the cue (P5) — the tab the
 * window remembers, the locked read-only rule, and the two hosts mounting this one editor.
 */

const landed = () => ({ unwrap: () => Promise.resolve() })
const patchCue = vi.fn(landed)
const patchEntry = vi.fn(landed)
let current: Cue

vi.mock('@/store/cues', () => ({
  usePatchProjectCueMutation: () => [patchCue],
  useProjectCueCookedQuery: () => ({ data: { cueId: 1, rows: [{}, {}, {}] } }),
}))
vi.mock('@/store/cueStacks', () => ({ usePatchCueStackEntryMutation: () => [patchEntry] }))
vi.mock('@/store/effects', () => ({ useSetCueEventsMutation: () => [vi.fn()] }))
vi.mock('@/store/errorToastMiddleware', () => ({ ignoreReportedError: () => {} }))
vi.mock('@/components/cues/CueRowParts', () => ({
  CueStatePip: () => <div data-testid="pip" />,
  CueTargetChip: () => <div />,
  useExpandedCue: () => ({ cueData: current, targets: [], isFetching: false }),
}))
vi.mock('./CueValuesGrid', () => ({ CueValuesGrid: () => <div data-testid="values" /> }))
// The Values grid's store is pinned in `CueValuesGrid.test.tsx`; here it is the provider alone.
vi.mock('./CueRowStore', () => ({
  CueRowStoreProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useCueRowSaveState: () => 'clean',
}))
vi.mock('@/components/cues/CueDetailContent', () => ({
  CueLayersReadout: () => <div data-testid="looks" />,
  CueEffectsReadout: () => <div data-testid="fx" />,
}))
vi.mock('@/components/scenery/CueSceneryEditor', () => ({
  CueSceneryEditorBody: () => <div data-testid="scenery-editor" />,
}))
vi.mock('@/components/scenery/SceneryReadout', () => ({ CueSceneryReadout: () => <div data-testid="scenery-readout" /> }))
vi.mock('@/components/effects/CueEventsEditor', () => ({
  CueEventsEditor: () => <div data-testid="events-editor" />,
  CueEventsReadout: () => <div data-testid="events-readout" />,
}))
vi.mock('@/components/hand/HandLayerTargets', () => ({ HandCueLayerStrip: () => null }))
vi.mock('@/components/busking/AddToBuskPageMenu', () => ({ AddToBuskPageMenu: () => <div data-testid="busk-append" /> }))
vi.mock('@/components/cues/CueTriggerEditor', () => ({ CueTriggerEditor: () => null }))
vi.mock('@/components/SaveStatusIndicator', () => ({ SaveStatusIndicator: () => <span data-testid="save-state" /> }))
// The card's own row and strip, for the two-hosts test.
vi.mock('@dnd-kit/sortable', () => ({
  useSortable: () => ({ attributes: {}, listeners: {}, setNodeRef: vi.fn(), transform: null, transition: undefined, isDragging: false }),
}))
vi.mock('@dnd-kit/utilities', () => ({ CSS: { Transform: { toString: () => undefined } } }))
vi.mock('@/components/runner/CueActions', () => ({ CueActions: () => <div data-testid="cue-actions" /> }))
vi.mock('@/hooks/useCueFade', () => ({ useCueFade: () => ({ fadeProgress: null, fadeRemainMs: null }) }))

import { CueEditor } from './CueEditor'
import { cueEditorTabStore } from './cueEditorTab'
import { CueCardEditor } from '@/components/runner/CueCardEditor'
import { CueEditorPane } from '@/components/runner/CueEditorPane'

function makeCue(over: Partial<Cue> = {}): Cue {
  return {
    id: 1,
    name: 'Tabs out',
    layers: [],
    adHocEffects: [],
    propertyAssignments: [],
    triggers: [],
    cueStackId: 7,
    cueStackName: 'Act 1',
    entryId: 101,
    sortOrder: 0,
    autoAdvance: false,
    autoAdvanceDelayMs: null,
    fadeDurationMs: 2000,
    fadeCurve: 'LINEAR',
    cueNumber: '1',
    cueNumberAuto: false,
    entries: [
      { entryId: 101, stackId: 7, stackName: 'Act 1', sortOrder: 0, cueNumber: '1', cueNumberAuto: false, autoAdvance: false, autoAdvanceDelayMs: null },
      { entryId: 205, stackId: 8, stackName: 'Act 2', sortOrder: 3, cueNumber: '0', cueNumberAuto: false, autoAdvance: false, autoAdvanceDelayMs: null },
    ],
    notes: null,
    cueType: 'STANDARD',
    canEdit: true,
    canDelete: true,
    ...over,
  }
}

function draw(over: Partial<React.ComponentProps<typeof CueEditor>> = {}) {
  return render(<CueEditor projectId={1} cueId={1} entryId={101} locked={false} host="card" {...over} />)
}

/** Open an inline field by its visible face, type, and press Enter. */
function edit(label: string, value: string) {
  fireEvent.click(screen.getByRole('button', { name: new RegExp(`^${label}:`) }))
  const input = screen.getByRole('textbox', { name: label })
  fireEvent.change(input, { target: { value } })
  fireEvent.keyDown(input, { key: 'Enter' })
}

beforeEach(() => {
  current = makeCue()
  sessionStorage.clear()
  cueEditorTabStore.reset()
})
afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('CueEditor', () => {
  it('writes the number and the follow to the entry, by its id, and nothing to the cue', () => {
    draw()
    edit('entry cue number', '14A')
    expect(patchEntry).toHaveBeenLastCalledWith({ projectId: 1, stackId: 7, entryId: 101, cueId: 1, cueNumber: '14A' })
    edit('entry follow', '3s')
    expect(patchEntry).toHaveBeenLastCalledWith({
      projectId: 1,
      stackId: 7,
      entryId: 101,
      cueId: 1,
      autoAdvance: true,
      autoAdvanceDelayMs: 3000,
    })
    expect(patchCue).not.toHaveBeenCalled()
  })

  it('writes the name and the fade to the cue, and nothing to the entry', () => {
    draw()
    edit('cue name', 'Tabs in')
    expect(patchCue).toHaveBeenLastCalledWith({ projectId: 1, cueId: 1, name: 'Tabs in' })
    edit('cue fade duration', '4s')
    expect(patchCue).toHaveBeenLastCalledWith({ projectId: 1, cueId: 1, fadeDurationMs: 4000 })
    expect(patchEntry).not.toHaveBeenCalled()
  })

  it('a follow of 0 fires at once — it writes a 0 delay, not "no follow"', () => {
    draw()
    edit('entry follow', '0')
    expect(patchEntry).toHaveBeenLastCalledWith({
      projectId: 1,
      stackId: 7,
      entryId: 101,
      cueId: 1,
      autoAdvance: true,
      autoAdvanceDelayMs: 0,
    })
    current = makeCue({ autoAdvance: true, autoAdvanceDelayMs: 0 })
    cleanup()
    draw()
    edit('entry follow', '')
    expect(patchEntry).toHaveBeenLastCalledWith({ projectId: 1, stackId: 7, entryId: 101, cueId: 1, autoAdvance: false })
  })

  it('commits a header field half-typed when its host goes — a GO collapsing a live card', () => {
    const view = draw()
    fireEvent.click(screen.getByRole('button', { name: /^cue name:/ }))
    fireEvent.change(screen.getByRole('textbox', { name: 'cue name' }), { target: { value: 'Tabs in' } })
    expect(patchCue).not.toHaveBeenCalled()
    view.unmount()
    expect(patchCue).toHaveBeenCalledWith({ projectId: 1, cueId: 1, name: 'Tabs in' })
  })

  it('says why another project’s cue reads, rather than offering an unlock', () => {
    current = makeCue({ canEdit: false })
    draw()
    expect(screen.queryByRole('button', { name: /^cue name:/ })).toBeNull()
    expect(document.querySelector('[data-cue-editor-entry]')?.getAttribute('title')).toMatch(/another project/)
  })

  it('gives each editor of one cue its own field ids', () => {
    cueEditorTabStore.set('cue')
    render(
      <>
        <CueEditor projectId={1} cueId={1} entryId={101} locked={false} host="card" />
        <CueEditor projectId={1} cueId={1} entryId={205} locked={false} host="card" />
      </>,
    )
    const notes = screen.getAllByLabelText('Notes')
    expect(notes).toHaveLength(2)
    expect(notes[0].id).not.toBe(notes[1].id)
  })

  it('is a key region: the cue sheet beside it does not hear its keys', () => {
    draw()
    expect(document.querySelector('[data-cue-editor]')).toHaveAttribute('data-key-region')
  })

  it('names the stack beside the number, draws the shared chip, and the save state', () => {
    draw()
    const header = document.querySelector('[data-cue-editor-header]') as HTMLElement
    const entry = header.querySelector('[data-cue-editor-entry]') as HTMLElement
    expect(within(entry).getByText('Act 1')).toBeTruthy()
    expect(within(entry).getAllByText('Q1').length).toBeGreaterThan(0)
    expect(within(header).getByRole('button', { name: /in 2 stacks/ }).getAttribute('title')).toBe('Also Act 2 · Q0')
    expect(within(header).getByTestId('save-state')).toBeTruthy()
  })

  it('has no number or follow to write for a cue read with no context entry, and says why', () => {
    current = makeCue({ cueStackId: null, entryId: null })
    draw({ entryId: null })
    // Plain text, not a field — and the entry half says why.
    expect(screen.queryByRole('button', { name: /^entry cue number:/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /^entry follow:/ })).toBeNull()
    expect(document.querySelector('[data-cue-editor-entry]')?.getAttribute('title')).toMatch(/several places/)
    // The cue's own half still edits.
    expect(screen.getByRole('button', { name: /^cue name:/ })).toBeTruthy()
  })

  it('draws the six tabs with their counts, Values counting what GO lands', () => {
    current = makeCue({ layers: [{} as never, {} as never], scenery: [{} as never] })
    draw()
    const tabs = screen.getAllByRole('tab').map((t) => t.textContent)
    expect(tabs).toEqual(['Values3', 'Looks2', 'FX0', 'Scenery1', 'Events0', 'Cue'])
  })

  it('remembers the tab per window, and a new editor opens on it; an open one keeps its own', () => {
    const first = draw()
    expect(screen.getByTestId('values')).toBeTruthy()
    fireEvent.mouseDown(screen.getByRole('tab', { name: /Scenery/ }))
    expect(screen.getByTestId('scenery-editor')).toBeTruthy()
    expect(sessionStorage.getItem('cueEditor.tab')).toBe('"scenery"')
    // A second editor beside it opens on Scenery; moving it to Cue leaves the first where it is.
    const second = render(<CueEditor projectId={1} cueId={1} entryId={101} locked={false} host="pane" />)
    const secondEditor = second.container.querySelector('[data-cue-editor-host="pane"]') as HTMLElement
    expect(within(secondEditor).getByTestId('scenery-editor')).toBeTruthy()
    fireEvent.mouseDown(within(secondEditor).getByRole('tab', { name: /^Cue$/ }))
    const firstEditor = first.container.querySelector('[data-cue-editor-host="card"]') as HTMLElement
    expect(within(firstEditor).getByTestId('scenery-editor')).toBeTruthy()
  })

  it('opens on the tab a host asks for', () => {
    const view = draw({ tabRequest: { tab: 'fx', seq: 1 } })
    expect(screen.getByTestId('fx')).toBeTruthy()
    view.rerender(<CueEditor projectId={1} cueId={1} entryId={101} locked={false} host="card" tabRequest={{ tab: 'looks', seq: 2 }} />)
    expect(screen.getByTestId('looks')).toBeTruthy()
  })

  it('locked, reads and writes nothing: the header is inert and the editing tabs are read-outs', () => {
    current = makeCue({ notes: 'Called on the bow' })
    draw({ locked: true })
    // Plain text where unlocked there are fields.
    expect(screen.queryByRole('button', { name: /^cue name:/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /^entry cue number:/ })).toBeNull()
    expect(screen.getByText('Tabs out')).toBeTruthy()
    expect(screen.getByRole('combobox', { name: 'cue fade curve' })).toBeDisabled()
    fireEvent.mouseDown(screen.getByRole('tab', { name: /Scenery/ }))
    expect(screen.queryByTestId('scenery-editor')).toBeNull()
    fireEvent.mouseDown(screen.getByRole('tab', { name: /Events/ }))
    expect(screen.queryByTestId('events-editor')).toBeNull()
    fireEvent.mouseDown(screen.getByRole('tab', { name: /^Cue$/ }))
    expect(screen.getByText('Called on the bow')).toBeTruthy()
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.queryByTestId('busk-append')).toBeNull()
  })

  it('the card and the table pane mount this one editor', () => {
    const row: CueStackCueRow = {
      id: 1,
      entryId: 101,
      name: 'Tabs out',
      sortOrder: 0,
      layerCount: 0,
      adHocEffectCount: 0,
      autoAdvance: false,
      autoAdvanceDelayMs: null,
      fadeDurationMs: 2000,
      fadeCurve: 'LINEAR',
      cueNumber: '1',
      cueNumberAuto: false,
      notes: null,
      cueType: 'STANDARD',
    }
    const column = { current: document.createElement('div') }
    const sheet = { current: document.createElement('div') }
    render(
      <>
        <CueCardEditor cue={row} stackId={7} projectId={1} expanded onToggleExpanded={() => {}} />
        <CueEditorPane projectId={1} cueId={1} entryId={101} locked={false} columnRef={column} sheetRef={sheet} />
      </>,
    )
    const editors = [...document.querySelectorAll('[data-cue-editor]')]
    expect(editors.map((e) => e.getAttribute('data-cue-editor-host'))).toEqual(['card', 'pane'])
    for (const editor of editors) {
      expect(editor.getAttribute('data-cue-editor')).toBe('1')
      expect(editor.querySelector('[data-cue-editor-header]')).not.toBeNull()
      expect(within(editor as HTMLElement).getAllByRole('tab')).toHaveLength(6)
    }
  })
})
