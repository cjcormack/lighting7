// @vitest-environment jsdom
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ProjectScriptDetail } from '@/api/projectApi'
import { fakePlayground, mounts, resetKotlinPlaygroundFake } from '@/test/kotlinPlaygroundFake'

/**
 * The two things the sheet promises about an edit in progress, both of which the widget's 500 ms
 * trailing-edge `onChange` used to break — and neither of which any test covered.
 *
 * Reset had never been able to work at all: the wrapper's update path early-returned on an
 * already-initialized node, so nothing pushed the reverted body back into a live editor, and the
 * next keystroke handed the edited body straight back and re-dirtied the form. The guard failed
 * more narrowly: type, then hit Escape inside the debounce window, and the sheet closed with no
 * question asked because the form had not been told about the edit yet.
 *
 * Both are pinned through the real wrapper against a faked widget, because both bugs lived in the
 * seam between the two rather than in either half.
 */

const noop = () => {}
const idleMutation = { data: undefined, isUninitialized: true, isLoading: false, reset: noop }

// Hoisted so their identity is stable across renders: `ScriptForm` seeds its form fields from an
// effect that depends on `resetCompile`/`resetRun`, and a fresh function per render would re-seed
// on every render and wipe whatever had been typed.
const runCreate = vi.fn()
const runSave = vi.fn()
const runDelete = vi.fn()
const runCompile = vi.fn()
const runRun = vi.fn()

vi.mock('@/store/projects', () => ({
  useCreateProjectScriptMutation: () => [runCreate, { isLoading: false }],
  useSaveProjectScriptMutation: () => [runSave, { isLoading: false }],
  useDeleteProjectScriptMutation: () => [runDelete, { isLoading: false }],
  useCompileProjectScriptMutation: () => [runCompile, idleMutation],
  useRunProjectScriptMutation: () => [runRun, idleMutation],
}))
vi.mock('@/CopyScriptDialog', () => ({ default: () => null }))
vi.mock('kotlin-playground', () => ({
  default: (node: HTMLElement, options: Record<string, unknown>) => fakePlayground(node, options),
}))

const { ScriptForm } = await import('./ScriptForm')

const script: ProjectScriptDetail = {
  id: 7,
  name: 'House Lights',
  script: 'val x = 1',
  scriptType: 'GENERAL',
  canEdit: true,
  canDelete: true,
}

/** Renders the sheet on an existing script and waits for the lazy editor chunk to arrive. */
async function renderForm(onOpenChange = vi.fn()) {
  render(
    <ScriptForm
      open
      onOpenChange={onOpenChange}
      script={script}
      projectId={1}
      isCurrentProject
    />,
  )

  await waitFor(() => expect(mounts).toHaveLength(1))
  const editor = mounts[0].fragment!.codemirror
  return { editor, onOpenChange }
}

beforeEach(() => {
  resetKotlinPlaygroundFake()
  vi.clearAllMocks()
})

describe('ScriptForm', () => {
  it('takes the edit back out of the editor on Reset', async () => {
    const { editor } = await renderForm()

    act(() => editor.type(' // edited'))
    expect(editor.getValue()).toBe('val x = 1 // edited')

    fireEvent.click(screen.getByRole('button', { name: 'Reset' }))

    expect(editor.getValue()).toBe('val x = 1')
  })

  it('leaves the form clean after a Reset, rather than re-dirtying on the echo', async () => {
    const { editor } = await renderForm()

    act(() => editor.type(' // edited'))
    fireEvent.click(screen.getByRole('button', { name: 'Reset' }))

    // The reverting write is a `setValue`, which CodeMirror reports as a change; unsuppressed it
    // came back in as a user edit and undid the Reset.
    expect(screen.getByRole('button', { name: 'Reset' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
  })

  it('asks before discarding when Escape follows a keystroke immediately', async () => {
    const { onOpenChange } = await renderForm()

    act(() => mounts[0].fragment!.codemirror.type('!'))
    fireEvent.keyDown(document, { key: 'Escape' })

    expect(await screen.findByText('Discard changes?')).toBeInTheDocument()
    expect(onOpenChange).not.toHaveBeenCalled()
  })

  it('still closes on Escape when nothing has been typed', async () => {
    const { onOpenChange } = await renderForm()

    fireEvent.keyDown(document, { key: 'Escape' })

    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
    expect(screen.queryByText('Discard changes?')).toBeNull()
  })

  it('compiles what is in the editor now, not what it held 500 ms ago', async () => {
    const { editor } = await renderForm()

    act(() => editor.type(' + 1'))
    fireEvent.click(screen.getByRole('button', { name: 'Compile' }))

    expect(runCompile).toHaveBeenCalledWith({
      projectId: 1,
      script: 'val x = 1 + 1',
      scriptType: 'GENERAL',
    })
  })
})
