// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { ComponentProps } from 'react'
import type { CueStack } from '../../api/cueStacksApi'
import type { FlatCue } from '../../lib/promptBook/desync'
const selection = vi.hoisted(() => ({ mask: null as string[] | null }))
vi.mock('@/hooks/useWindowMaskReader', () => ({ useWindowMaskReader: () => () => selection.mask }))

import { CueAnchorPickerSheet } from './CueAnchorPickerSheet'

afterEach(() => {
  cleanup()
  selection.mask = null
})

const stack = { id: 7, name: 'Act 1', type: 'STACK', cues: [] } as unknown as CueStack
const cue = {
  entryId: 11,
  cueId: 3,
  stackId: 7,
  stackName: 'Act 1',
  label: 'Q1',
  name: 'Preset',
} as unknown as FlatCue

function renderSheet(props: Partial<ComponentProps<typeof CueAnchorPickerSheet>> = {}) {
  const onCreateCue = vi.fn()
  render(
    <CueAnchorPickerSheet
      open
      cueOrder={[cue]}
      anchorByEntry={new Map()}
      stacks={[stack]}
      defaultStackId={7}
      existingCueNames={new Set()}
      preselectEntryId={null}
      openOnRecord={false}
      recordUnavailable={null}
      onPick={() => {}}
      onCreateCue={onCreateCue}
      onEditCue={() => {}}
      onClose={() => {}}
      {...props}
    />,
  )
  return { onCreateCue }
}

describe('CueAnchorPickerSheet · recording the programmer', () => {
  it('opens on the create form recording the programmer from "Record cue"', () => {
    const { onCreateCue } = renderSheet({ openOnRecord: true })
    expect(screen.getByText('Record a cue for this selection')).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'The programmer' })).toBeChecked()
    fireEvent.click(screen.getByRole('button', { name: 'Record & anchor' }))
    expect(onCreateCue).toHaveBeenCalledWith(
      expect.objectContaining({ record: true, name: 'New Cue', stack: { kind: 'existing', id: 7 }, mask: undefined }),
    )
  })

  it('keeps "New cue" an empty cue, with the programmer one press away', async () => {
    const { onCreateCue } = renderSheet()
    fireEvent.click(screen.getByRole('button', { name: 'New cue' }))
    expect(screen.getByRole('radio', { name: 'Empty' })).toBeChecked()
    // Awaited: the form holds further presses until the create it sent has settled.
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Create & anchor' })))
    expect(onCreateCue).toHaveBeenLastCalledWith(expect.objectContaining({ record: false }))

    fireEvent.click(screen.getByRole('radio', { name: 'The programmer' }))
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Record & anchor' })))
    expect(onCreateCue).toHaveBeenLastCalledWith(expect.objectContaining({ record: true }))
  })

  it('opens on every attribute over a masked selection', () => {
    selection.mask = ['COLOUR']
    const { onCreateCue } = renderSheet({ openOnRecord: true })
    expect(screen.getByRole('switch', { name: 'All attributes' })).toHaveAttribute('aria-checked', 'true')
    fireEvent.click(screen.getByRole('button', { name: 'Record & anchor' }))
    expect(onCreateCue).toHaveBeenCalledWith(expect.objectContaining({ record: true, mask: undefined }))
  })

  it('says why the programmer cannot be recorded, and creates an empty cue', () => {
    const { onCreateCue } = renderSheet({ openOnRecord: true, recordUnavailable: 'The programmer is empty.' })
    expect(screen.getByRole('radio', { name: 'The programmer' })).toBeDisabled()
    expect(screen.getByText('The programmer is empty.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Create & anchor' }))
    expect(onCreateCue).toHaveBeenCalledWith(expect.objectContaining({ record: false }))
  })
})
