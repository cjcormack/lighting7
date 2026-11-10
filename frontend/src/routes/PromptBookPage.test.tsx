// @vitest-environment jsdom
import { forwardRef, useImperativeHandle } from 'react'
import { act, cleanup, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router'
import { Provider } from 'react-redux'
import type { CueStack } from '@/api/cueStacksApi'
import type { PromptBookDetails, Region } from '@/api/promptBooksApi'
import { cueRow } from '@/test/cueEntries'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

/**
 * The page's follow scroll against a selection in the script. The viewer is stubbed to what the page reads of it — its `scrollToRegion` and its hold — so what is
 * under test is when the page asks it to scroll.
 */

// ── A show of three cues, each anchored on its own page. ──
const stack: CueStack = {
  id: 1,
  name: 'Act 1',
  loop: false,
  sortOrder: 0,
  type: 'STACK',
  label: null,
  cues: [cueRow(1), cueRow(2), cueRow(3)],
  activeCueId: null,
  nextCueId: null,
  activeEntryId: null,
  nextEntryId: null,
} as unknown as CueStack
const regionOf = (page: number): Region => [{ page, x: 0.1, y: 0.2, w: 0.6, h: 0.02 }]
const book: PromptBookDetails = {
  scriptHash: 'a'.repeat(64),
  scriptFileName: 'script.pdf',
  pageCount: 8,
  coverPages: 0,
  anchors: [1, 2, 3].map((id) => ({ cueId: id, entryId: id + 100, label: `Q${id}`, region: regionOf(id) })),
  annotations: [],
  canEdit: true,
}

const transport = { activeEntryId: 101 as number | null }
const mutation = () => [vi.fn(() => ({ unwrap: () => Promise.resolve({}) }))]

vi.mock('../store/promptBooks', () => ({
  useProjectPromptBookQuery: () => ({ data: book, isLoading: false, error: undefined, refetch: vi.fn() }),
  useUpsertAnchorMutation: () => mutation(),
  useDeleteAnchorMutation: () => mutation(),
}))
vi.mock('../store/projects', () => ({
  useCurrentProjectQuery: () => ({ data: { id: 1, inProduction: false } }),
  useProjectQuery: () => ({ data: { id: 1, name: 'Show' } }),
}))
vi.mock('../store/venues', () => ({ useVenueMode: () => ({ isVenue: false }) }))
vi.mock('../components/programmer/useProgrammerContent', () => ({
  useProgrammerContent: () => ({ hasContent: false }),
}))
vi.mock('../store/cueStacks', () => ({
  useProjectProgramStateQuery: () => ({ data: { activeStackId: 1, canEdit: true } }),
  useProjectCueStackListQuery: () => ({ data: [stack] }),
  useActivateProgramMutation: () => mutation(),
  useDeactivateProgramMutation: () => mutation(),
  usePatchCueStackEntryMutation: () => mutation(),
  useCreateProjectCueStackMutation: () => mutation(),
  useReorderCueStackCuesMutation: () => mutation(),
}))
vi.mock('../store/cues', () => ({
  usePatchProjectCueMutation: () => mutation(),
  useCreateProjectCueMutation: () => mutation(),
}))
vi.mock('../store/programmerOps', () => ({ useRecordProgrammerMutation: () => mutation() }))
vi.mock('../store/stageElements', () => ({ useStageElementListQuery: () => ({ data: [] }) }))
vi.mock('../hooks/useNextGoPreview', () => ({ useOnGoScenery: () => ({ cueLabel: null, moves: [] }) }))
vi.mock('../hooks/useContainerBand', () => ({ useNarrowContainer: () => [vi.fn(), false] }))
vi.mock('@/components/cues/CueNumberPlacement', () => ({
  useCueNumberPlacement: () => ({ writeNumber: vi.fn(), dialog: null }),
}))
vi.mock('../hooks/useShowBarProps', () => ({
  useShowBarProps: () => ({
    transport: {
      activeStackId: 1,
      activeStack: stack,
      activeEntryId: transport.activeEntryId,
      standbyEntryId: null,
      goDisabled: false,
      go: vi.fn(),
      back: vi.fn(),
      setStandby: vi.fn(),
    },
    showBarProps: {},
  }),
}))
vi.mock('../lib/promptBook/useScriptDocument', () => ({
  useScriptDocument: () => ({
    loadState: 'ready',
    retryNonce: 0,
    hashMismatch: false,
    onDocumentError: vi.fn(),
    retry: vi.fn(),
    reupload: vi.fn(),
    reuploadError: null,
    setCoverPages: vi.fn(),
    importBook: vi.fn(),
    importError: null,
    uploading: false,
    settingBook: false,
  }),
}))
vi.mock('../lib/promptBook/useAnnotationEditor', () => ({
  useAnnotationEditor: () => ({
    sheetOpen: false,
    cutConfirmOpen: false,
    mode: 'create',
    kind: 'NOTE',
    kindLabel: 'Note',
    text: '',
    tone: 'NOTE',
    setText: vi.fn(),
    setTone: vi.fn(),
    create: vi.fn(),
    open: vi.fn(),
    close: vi.fn(),
    commit: vi.fn(),
    remove: vi.fn(),
  }),
}))

// ── The viewer, as the page sees it. ──
const viewer = {
  scrolledTo: [] as number[],
  onHoldChange: undefined as ((held: boolean, unmounted?: boolean) => void) | undefined,
}
vi.mock('../components/promptbook/ScriptViewer', () => ({
  ScriptViewer: forwardRef(function FakeViewer(
    props: { onHoldChange?: (held: boolean, unmounted?: boolean) => void },
    ref: React.Ref<{ scrollToRegion: (region: Region) => void }>,
  ) {
    viewer.onHoldChange = props.onHoldChange
    useImperativeHandle(ref, () => ({ scrollToRegion: (region) => viewer.scrolledTo.push(region[0].page) }), [])
    return <div data-testid="viewer" />
  }),
}))
vi.mock('../components/promptbook/CueAnchorPickerSheet', () => ({ CueAnchorPickerSheet: () => null }))
vi.mock('../components/ShowHeader', () => ({ ShowHeader: () => null }))
vi.mock('../components/ShowBar', () => ({ ShowBar: () => null }))
const toolbar = { jumpToLive: undefined as (() => void) | undefined }
vi.mock('../components/promptbook/PromptBookToolbar', () => ({
  PromptBookToolbar: (props: { onJumpToLive: () => void }) => {
    toolbar.jumpToLive = props.onJumpToLive
    return null
  },
}))
vi.mock('../components/runner/ShowLockControl', () => ({ ShowLockControl: () => null, NotInProductionLabel: () => null }))
vi.mock('../components/promptbook/ToolPalette', () => ({ ToolPalette: () => null }))
const rail = { cueClick: undefined as ((cue: { entryId: number }) => void) | undefined }
vi.mock('../components/promptbook/CueStackPanel', () => ({
  CueStackPanel: (props: { onCueClick: (cue: { entryId: number }) => void }) => {
    rail.cueClick = props.onCueClick
    return null
  },
}))
vi.mock('../components/promptbook/ScriptUploadCard', () => ({ ScriptUploadCard: () => null }))
vi.mock('@/components/ImmersiveEscape', () => ({ ImmersiveEscape: () => null }))

import { store } from '../store'
import { PromptBookViewerPage } from './PromptBookPage'

const page = () => (
  <Provider store={store}>
    <MemoryRouter initialEntries={['/projects/1/prompt-book']}>
      <Routes>
        <Route path="/projects/:projectId/prompt-book" element={<PromptBookViewerPage />} />
      </Routes>
    </MemoryRouter>
  </Provider>
)

beforeEach(() => {
  transport.activeEntryId = 101
  viewer.scrolledTo = []
  viewer.onHoldChange = undefined
})
afterEach(cleanup)

/** A GO: the live entry moves, and the page re-renders on it. */
function go(view: ReturnType<typeof render>, entryId: number) {
  transport.activeEntryId = entryId
  view.rerender(page())
}

describe('PromptBookPage follow scroll', () => {
  it('follows the live cue on every GO', () => {
    const view = render(page())
    expect(viewer.scrolledTo).toEqual([1])
    go(view, 102)
    go(view, 103)
    expect(viewer.scrolledTo).toEqual([1, 2, 3])
  })

  it('waits while the script holds a selection, then scrolls once to where the show is', () => {
    const view = render(page())
    act(() => viewer.onHoldChange!(true))

    go(view, 102)
    go(view, 103)
    expect(viewer.scrolledTo).toEqual([1])

    act(() => viewer.onHoldChange!(false))
    expect(viewer.scrolledTo).toEqual([1, 3])
  })

  it('drops a waiting follow when the operator moves the book themselves', () => {
    const view = render(page())
    act(() => viewer.onHoldChange!(true))
    go(view, 102)
    act(() => rail.cueClick!({ entryId: 103 }))
    act(() => viewer.onHoldChange!(false))
    expect(viewer.scrolledTo).toEqual([1, 3])

    act(() => viewer.onHoldChange!(true))
    go(view, 103)
    act(() => toolbar.jumpToLive!())
    act(() => viewer.onHoldChange!(false))
    expect(viewer.scrolledTo).toEqual([1, 3, 3])
  })

  it('drops a waiting follow when the viewer unmounts', () => {
    const view = render(page())
    act(() => viewer.onHoldChange!(true))
    go(view, 102)
    act(() => viewer.onHoldChange!(false, true))
    expect(viewer.scrolledTo).toEqual([1])
  })

  it('scrolls nothing when a hold ends with no GO behind it', () => {
    render(page())
    act(() => viewer.onHoldChange!(true))
    act(() => viewer.onHoldChange!(false))
    expect(viewer.scrolledTo).toEqual([1])
  })
})
