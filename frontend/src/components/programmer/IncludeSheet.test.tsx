// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { IncludeResponse } from '@/store/programmerOps'

/**
 * The Include sheet's ending: picking a cue includes it, closes the sheet and says what was
 * included as a toast — counting the scenery a cue brought with it (scenery-programmer plan
 * session 3), so a cue that only moves scenery does not read as nothing included. A refused
 * Include keeps the sheet open.
 */
const response = { current: null as Partial<IncludeResponse> | null }
const include = vi.fn((..._: unknown[]) => Promise.resolve(response.current))

vi.mock('sonner', () => ({ toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }))
vi.mock('@/store/cueStacks', () => ({
  useProjectCueStackListQuery: () => ({
    data: [{ id: 4, name: 'Act 1', type: 'STANDARD', cues: [{ id: 15, name: 'Storm', cueNumber: '15', cueType: 'STANDARD' }] }],
  }),
}))
vi.mock('@/store/looks', () => ({ useLookListQuery: () => ({ data: [] }) }))
vi.mock('./useInclude', () => ({
  useInclude: () => ({ include, isLoading: false, error: undefined, resetInclude: () => {} }),
}))

import { toast } from 'sonner'
import { IncludeSheet } from './IncludeSheet'

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

function includeStorm(onOpenChange: (open: boolean) => void) {
  render(<IncludeSheet open onOpenChange={onOpenChange} projectId={6} />)
  fireEvent.click(screen.getByRole('button', { name: /Storm/ }))
}

describe('IncludeSheet', () => {
  it('closes once the cue is included, counting the scenery it brought', async () => {
    response.current = { name: 'Storm', entriesWritten: 0, fxSpawned: 0, fxAlreadyRunning: 0, sceneryIncluded: 2 }
    const onOpenChange = vi.fn()
    includeStorm(onOpenChange)
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
    expect(include.mock.calls[0][0]).toEqual({ kind: 'CUE', cueId: 15 })
    expect(toast.success).toHaveBeenCalledWith('Included “Storm” — 0 values, 2 scenery changes.', { description: undefined })
  })

  it('says nothing about scenery when there was none, or from a desk that does not send it', async () => {
    response.current = { name: 'Storm', entriesWritten: 1, fxSpawned: 0, fxAlreadyRunning: 0 }
    const onOpenChange = vi.fn()
    includeStorm(onOpenChange)
    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Included “Storm” — 1 value.', { description: undefined }))
  })

  it('stays open when the Include is refused', async () => {
    response.current = null
    const onOpenChange = vi.fn()
    includeStorm(onOpenChange)
    await waitFor(() => expect(include).toHaveBeenCalled())
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(onOpenChange).not.toHaveBeenCalled()
    expect(toast.success).not.toHaveBeenCalled()
  })
})
