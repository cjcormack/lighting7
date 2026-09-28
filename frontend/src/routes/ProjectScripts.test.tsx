// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ProjectScriptDetail } from '../api/projectApi'
import type { CompileResult } from '../store/scripts'
import type { EffectLibraryEntry } from '../store/fixtureFx'

/** Each compile hangs until the test answers it, so the rows can be read between answers. */
const pendingCompiles: { body: unknown; resolve: (r: CompileResult) => void }[] = []
const compileScript = vi.fn(
  (body: unknown) => new Promise<CompileResult>((resolve) => pendingCompiles.push({ body, resolve })),
)
const runScript = vi.fn()
const saveScript = vi.fn((_body: unknown): Promise<unknown> => Promise.resolve({}))
const copyScript = vi.fn((_body: unknown): Promise<unknown> => Promise.resolve({}))
const deleteScript = vi.fn((_body: unknown): Promise<unknown> => Promise.resolve(undefined))
let isCurrent = true
let scripts: ProjectScriptDetail[] = []

vi.mock('sonner', () => ({ toast: { error: () => {}, info: () => {}, success: () => {} } }))
vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: ({ count, estimateSize }: { count: number; estimateSize: () => number }) => ({
    getTotalSize: () => count * estimateSize(),
    getVirtualItems: () =>
      Array.from({ length: count }, (_, index) => ({ index, key: index, start: index * estimateSize(), size: estimateSize() })),
    scrollToIndex: () => {},
  }),
}))
vi.mock('@/components/Breadcrumbs', () => ({ Breadcrumbs: () => <div /> }))
vi.mock('../components/scripts/ScriptForm', () => ({
  ScriptForm: ({ open, script }: { open: boolean; script: ProjectScriptDetail | null }) =>
    open ? <div data-testid="form">{script?.name ?? 'new'}</div> : null,
}))
vi.mock('../components/scripts/ScriptResultDialogs', () => ({ ScriptRunDialog: () => null }))
vi.mock('../store/projects', () => ({
  useCurrentProjectQuery: () => ({ data: { id: isCurrent ? 6 : 1, name: 'Experiment' }, isLoading: false }),
  useProjectQuery: () => ({ data: { id: 6, name: 'Experiment', isCurrent }, isLoading: false }),
  useProjectScriptsQuery: () => ({ data: scripts, isLoading: false }),
  useProjectListQuery: () => ({
    data: [
      { id: 6, name: 'Experiment', isCurrent },
      { id: 1, name: 'Hamlet', isCurrent: !isCurrent },
    ],
  }),
  useCompileProjectScriptMutation: () => [(body: unknown) => ({ unwrap: () => compileScript(body) })],
  useRunProjectScriptMutation: () => [
    (body: unknown) => runScript(body),
    { data: undefined, isUninitialized: true, isLoading: false, reset: () => {} },
  ],
  useSaveProjectScriptMutation: () => [(body: unknown) => ({ unwrap: () => saveScript(body) })],
  useCopyScriptMutation: () => [(body: unknown) => ({ unwrap: () => copyScript(body) })],
  useDeleteProjectScriptMutation: () => [(body: unknown) => ({ unwrap: () => deleteScript(body) })],
}))

function libraryEntry(name: string, sourceDefinitionId: number): EffectLibraryEntry {
  return {
    name,
    category: 'colour',
    outputType: 'COLOUR',
    effectMode: 'STANDARD',
    parameters: [],
    compatibleProperties: ['rgbColour'],
    source: 'USER',
    sourceDefinitionId,
  }
}

// Script 3 registers Warm Flicker; custom definition 3's effect carries a 3 as well, and is not its.
vi.mock('../store/fixtureFx', () => ({
  useEffectLibraryQuery: () => ({
    data: [libraryEntry('WarmFlicker', 3), libraryEntry('RandomFlicker', 3)],
  }),
}))
vi.mock('../store/fxDefinitions', () => ({
  useFxDefinitionListQuery: () => ({ data: [{ id: 3, effectId: 'RandomFlicker', name: 'Random Flicker' }] }),
}))

import ProjectScripts from './ProjectScripts'
import { resetEditorSurfaceMedia } from '@/components/editor/EditorSurface'

function script(id: number, name: string, over: Partial<ProjectScriptDetail> = {}): ProjectScriptDetail {
  return { id, name, script: `// ${name}\nval a = ${id}\n`, scriptType: 'GENERAL', canEdit: true, canDelete: true, ...over }
}

const SCRIPTS = [
  script(1, 'Startup'),
  script(2, 'Chorus Hit', { scriptType: 'FX_APPLICATION' }),
  script(3, 'Warm Flicker', { scriptType: 'FX_DEFINITION' }),
  script(4, 'Test Pattern'),
]

const tree = () => (
  <MemoryRouter initialEntries={['/projects/6/scripts']}>
    <Routes>
      <Route path="/projects/:projectId/scripts" element={<ProjectScripts />} />
    </Routes>
  </MemoryRouter>
)

function row(id: number): HTMLElement {
  return document.querySelector(`[data-row-id="script:${id}"]`) as HTMLElement
}

/** A read-out's text — read-outs carry no `data-cell`, so by the column's position in the row. */
function readOut(id: number, col: 'type' | 'lines' | 'check' | 'usedBy'): HTMLElement {
  const index = { type: 0, lines: 1, check: 2, usedBy: 3 }[col]
  const cells = [...row(id).children].filter((el) => !el.hasAttribute('data-first-column'))
  return cells[index] as HTMLElement
}

function check(id: number): HTMLElement {
  return readOut(id, 'check')
}

function selectRows(...ids: number[]) {
  ids.forEach((id, i) => fireEvent.click(row(id).querySelector('[data-first-column]')!, { metaKey: i > 0 }))
}

async function answer(result: CompileResult) {
  const next = pendingCompiles.shift()!
  await act(async () => next.resolve(result))
}

beforeEach(() => {
  isCurrent = true
  scripts = SCRIPTS
  localStorage.clear()
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
  resetEditorSurfaceMedia()
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  pendingCompiles.length = 0
  for (const fn of [compileScript, runScript, saveScript, copyScript, deleteScript]) fn.mockClear()
})

describe('Scripts sheet', () => {
  it('groups under type dividers, and reads Used by for a definition script alone', () => {
    render(tree())
    expect(document.querySelector('[data-row-id="type:FX_DEFINITION"]')).toHaveTextContent('FX Definition · 1')
    expect(readOut(3, 'usedBy')).toHaveTextContent('registers Warm Flicker')
    // Random Flicker is a custom definition's effect, not script 3's, though both carry a 3.
    expect(readOut(3, 'usedBy')).not.toHaveTextContent('Random Flicker')
    expect(readOut(2, 'usedBy')).toHaveTextContent('—')
    expect(readOut(1, 'lines')).toHaveTextContent('2')
  })

  it('Compile sends each script’s text and type, one at a time, and fills Check as each answer arrives', async () => {
    render(tree())
    selectRows(1, 2, 4)
    fireEvent.click(screen.getByRole('button', { name: 'Compile' }))
    // Visible order: the General divider's two by name, then the FX application.
    await waitFor(() => expect(compileScript).toHaveBeenCalledTimes(1))
    expect(compileScript.mock.calls[0][0]).toEqual({ projectId: 6, script: SCRIPTS[0].script, scriptType: 'GENERAL' })
    expect(check(1)).toHaveTextContent('Compiling…')
    expect(check(4)).toHaveTextContent('Queued')
    expect(check(2)).toHaveTextContent('Queued')
    expect(check(3)).toHaveTextContent('not checked')

    await answer({ success: true, messages: [] })
    expect(check(1)).toHaveTextContent('Compiles')
    expect(check(4)).toHaveTextContent('Compiling…')
    expect(compileScript).toHaveBeenCalledTimes(2)

    await answer({
      success: false,
      messages: [
        { severity: 'ERROR', message: 'Unresolved reference: foo', location: '14:5' },
        { severity: 'ERROR', message: 'Type mismatch', location: '20:1' },
      ],
    })
    expect(check(4)).toHaveTextContent('2 errors · line 14')
    expect(check(4).querySelector('[title]')).toHaveAttribute('title', 'Unresolved reference: foo')

    await answer({ success: true, messages: [] })
    expect(compileScript.mock.calls[2][0]).toEqual({ projectId: 6, script: SCRIPTS[1].script, scriptType: 'FX_APPLICATION' })
    expect(check(2)).toHaveTextContent('Compiles')
    expect(screen.getByText(/3 checked · 1 failed/)).toBeInTheDocument()
  })

  it('an edited script reads *not checked* again; a renamed one keeps its check', async () => {
    const { rerender } = render(tree())
    selectRows(1, 4)
    fireEvent.click(screen.getByRole('button', { name: 'Compile' }))
    await waitFor(() => expect(pendingCompiles).toHaveLength(1))
    await answer({ success: false, messages: [{ severity: 'ERROR', message: 'bad', location: '2:1' }] })
    await answer({ success: true, messages: [] })
    expect(check(1)).toHaveTextContent('1 error · line 2')
    expect(check(4)).toHaveTextContent('Compiles')

    scripts = SCRIPTS.map((s) =>
      s.id === 1 ? { ...s, script: 'val fixed = 1\n' } : s.id === 4 ? { ...s, name: 'Test Pattern B' } : s,
    )
    rerender(tree())
    expect(check(1)).toHaveTextContent('not checked')
    expect(check(4)).toHaveTextContent('Compiles')
  })

  it('renames with the whole row — name, script and type — so the type is never defaulted', () => {
    render(tree())
    fireEvent.doubleClick(within(row(3)).getByText('Warm Flicker'))
    const field = screen.getByLabelText('Script name') as HTMLInputElement
    fireEvent.change(field, { target: { value: 'Warm Flicker v2' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(saveScript).toHaveBeenCalledWith({
      projectId: 6,
      scriptId: 3,
      name: 'Warm Flicker v2',
      script: SCRIPTS[2].script,
      scriptType: 'FX_DEFINITION',
    })
  })

  it('Run takes one script, by id, and is refused over two', () => {
    render(tree())
    selectRows(1, 4)
    expect(screen.getByRole('button', { name: 'Run' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Run' })).toHaveAttribute('title', 'Run takes one script — select just one')
    selectRows(2)
    fireEvent.click(screen.getByRole('button', { name: 'Run' }))
    expect(runScript).toHaveBeenCalledWith({ projectId: 6, script: SCRIPTS[1].script, scriptType: 'FX_APPLICATION', scriptId: 2 })
  })

  it('Delete holds a definition script whose effects are live for one question, and sends the rest', async () => {
    render(tree())
    selectRows(1, 3)
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(deleteScript).toHaveBeenCalledTimes(1))
    expect(deleteScript).toHaveBeenCalledWith({ projectId: 6, scriptId: 1 })
    const listed = await screen.findByTestId('batch-delete-in-use')
    expect(listed).toHaveTextContent('Warm Flicker')
    expect(listed).toHaveTextContent('registers Warm Flicker')
    fireEvent.click(screen.getByRole('button', { name: 'Delete anyway' }))
    await waitFor(() => expect(deleteScript).toHaveBeenCalledTimes(2))
    expect(deleteScript).toHaveBeenLastCalledWith({ projectId: 6, scriptId: 3 })
  })

  it('opens a row from ⏎, and a type chip narrows the sheet', () => {
    render(tree())
    selectRows(2)
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(screen.getByTestId('form')).toHaveTextContent('Chorus Hit')
    fireEvent.click(within(screen.getByRole('navigation', { name: 'Script type' })).getByRole('button', { name: /FX app/ }))
    expect(row(1)).toBeNull()
    expect(row(2)).not.toBeNull()
    expect(localStorage.getItem('scripts.type')).toBe('fx-application')
  })

  it('another project’s scripts are read-only, with Copy to… the one live verb', async () => {
    isCurrent = false
    render(tree())
    selectRows(1, 2)
    const reason = 'Experiment’s library — copy it here to edit'
    for (const name of ['Compile', 'Run', 'Delete']) {
      expect(screen.getByRole('button', { name })).toBeDisabled()
      expect(screen.getByRole('button', { name })).toHaveAttribute('title', reason)
    }
    expect(within(row(1)).queryByTitle('Double-click to rename')).toBeNull()
    expect(screen.queryByRole('button', { name: 'New script' })).toBeNull()
    expect(readOut(3, 'usedBy')).toHaveTextContent('—')

    fireEvent.click(screen.getByRole('button', { name: 'Copy to…' }))
    fireEvent.click(await screen.findByRole('button', { name: /^Copy/ }))
    await waitFor(() => expect(copyScript).toHaveBeenCalledTimes(2))
    expect(copyScript.mock.calls.map(([b]) => b)).toEqual([
      { projectId: 6, scriptId: 1, targetProjectId: 1, newName: undefined },
      { projectId: 6, scriptId: 2, targetProjectId: 1, newName: undefined },
    ])
  })
})
