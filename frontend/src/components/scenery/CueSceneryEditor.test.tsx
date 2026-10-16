// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { createRef } from 'react'

const cueQuery = vi.fn()
vi.mock('@/store/cues', () => ({
  useProjectCueQuery: (args: { cueId: number; entryId?: number }) => {
    cueQuery(args)
    // The desk answers what the cue tracks only at a named place when the cue sits in several.
    return {
      currentData:
        args.entryId === 205
          ? { trackedScenery: [{ elementUuid: 'moon', elementName: 'Moon', state: { visible: false }, fromCueLabel: '3' }] }
          : { trackedScenery: [] },
    }
  },
}))
vi.mock('@/store/stageElements', () => ({ useStageElementListQuery: () => ({ data: [] }) }))
vi.mock('@/store/scenery', () => ({ useSetCueSceneryMutation: () => [vi.fn()] }))
vi.mock('@/components/scenery/SceneryEditor', () => ({ SceneryEditor: () => <div data-testid="rows" /> }))
vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))

import { CueSceneryEditor } from './CueSceneryEditor'

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('CueSceneryEditor', () => {
  it('reads what a shared cue tracks at the entry it was opened from', () => {
    render(
      <CueSceneryEditor
        projectId={1}
        cue={{ id: 5, name: 'Night', cueNumber: '9', entryId: 205 }}
        anchorRef={createRef<HTMLElement>()}
        onClose={() => {}}
      />,
    )
    expect(cueQuery).toHaveBeenCalledWith({ projectId: 1, cueId: 5, entryId: 205 })
    expect(screen.getByText(/Tracked into it:/)).toHaveTextContent('(Q3)')
  })
})
