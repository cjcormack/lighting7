// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { StageElementDto } from '@/api/stageElementApi'
import type { ProgrammerScenery } from '@/api/sceneryApi'

/**
 * The Record sheets' *Record scenery too* row (scenery-programmer plan D7, session 3): absent when
 * the programmer holds no scenery, ticked by default when it holds some, its hint naming the held
 * pieces — and what each sheet sends for it.
 */
function element(over: Partial<StageElementDto>): StageElementDto {
  return {
    id: 1, uuid: 'e', name: 'E', kind: 'OBJECT', layer: 'SET',
    positionX: 0, positionY: 0, positionZ: 0, yawDeg: 0, widthM: 1, depthM: 1, heightM: 1,
    finishColour: null, finishPattern: null, emissive: false, params: {}, hidden: false, sortOrder: 0,
    ...over,
  }
}

const store = {
  programmer: { projectId: 6, elements: [] } as ProgrammerScenery,
  elements: [
    element({ id: 2, uuid: 'moon', name: 'Moon', positionZ: 3, params: { flies: true, states: { trimM: 7 } } }),
    element({ id: 3, uuid: 'tabs', name: 'House tabs', kind: 'DRAPE', layer: 'VENUE', params: { role: 'TABS', operation: 'DRAW' } }),
  ] as StageElementDto[],
}
const record = vi.fn((_: unknown) => Promise.resolve({ data: undefined }))
const recordLook = vi.fn((_: unknown) => ({
  unwrap: () =>
    Promise.resolve({
      look: { name: 'Night' }, created: true, rowsWritten: 0, rowsRemoved: 0, groupRowsEmitted: 0,
      skipped: [], programmerKeysRefreshed: 0, cuesRepublished: [], sceneryWritten: 1,
    }),
}))

vi.mock('react-redux', () => ({ useSelector: () => [] }))
vi.mock('@/store/selectionSlice', () => ({ selectTargetKeys: () => [] }))
vi.mock('@/store/programmer', () => ({ useProgrammerScenery: () => store.programmer }))
vi.mock('@/store/stageElements', () => ({ useStageElementListQuery: () => ({ data: store.elements }) }))
vi.mock('@/store/cueStacks', () => ({
  useProjectCueStackListQuery: () => ({ data: [{ id: 4, name: 'Main', type: 'STANDARD' }] }),
}))
vi.mock('@/store/looks', () => ({ useLookListQuery: () => ({ data: [] }) }))
vi.mock('@/store/fixtureFx', () => ({ useActiveEffectsQuery: () => ({ data: [] }) }))
vi.mock('./useLocalFamilyCounts', () => ({ useLocalFamilyCounts: () => undefined }))
vi.mock('@/store/programmerOps', () => ({
  useRecordProgrammerMutation: () => [record, { isLoading: false, error: undefined, reset: () => {} }],
  useRecordLookMutation: () => [recordLook, { isLoading: false, error: undefined, reset: () => {} }],
}))

import { RecordSheet } from './RecordSheet'
import { RecordLookSheet } from './RecordLookSheet'
import { describeSceneryWrite } from './RecordSceneryRow'

function hold(...uuids: string[]) {
  store.programmer = { projectId: 6, elements: uuids.map((elementUuid) => ({ elementUuid, state: { visible: true } })) }
}

function sceneryBox() {
  return screen.queryByRole('checkbox', { name: /record scenery too/i }) as HTMLInputElement | null
}

beforeEach(() => {
  record.mockClear()
  recordLook.mockClear()
  store.programmer = { projectId: 6, elements: [] }
})
afterEach(cleanup)

describe('the Record sheet', () => {
  it('draws no scenery row while nothing is held, and sends the default', async () => {
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    expect(sceneryBox()).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Record' }))
    await waitFor(() => expect(record).toHaveBeenCalled())
    expect(record.mock.calls[0][0]).toMatchObject({ scenery: true })
  })

  it('ticks the row by default when the programmer holds scenery, naming the pieces', async () => {
    hold('moon', 'tabs')
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    const box = sceneryBox()!
    expect(box.checked).toBe(true)
    expect(screen.getByTestId('record-scenery-row').textContent).toContain('Moon · House tabs')
    expect(screen.getByTestId('record-scenery-row').textContent).toContain('(2)')

    fireEvent.click(box)
    expect(box.checked).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Record' }))
    await waitFor(() => expect(record).toHaveBeenCalled())
    expect(record.mock.calls[0][0]).toMatchObject({ scenery: false })
  })

  it('ticks it again on the next open, whatever the last Record sent', () => {
    hold('moon')
    const { rerender } = render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    fireEvent.click(sceneryBox()!)
    expect(sceneryBox()!.checked).toBe(false)
    rerender(<RecordSheet open={false} onOpenChange={() => {}} projectId={6} />)
    rerender(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    expect(sceneryBox()!.checked).toBe(true)
  })

  it("leaves out another project's overlay", () => {
    store.programmer = { projectId: 9, elements: [{ elementUuid: 'moon', state: { trimM: 3 } }] }
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    expect(sceneryBox()).toBeNull()
  })
})

describe('the Record look sheet', () => {
  it('ticks the row by default and sends it, so a look of only the moon is a scenery look', async () => {
    hold('moon')
    render(<RecordLookSheet open onOpenChange={() => {}} projectId={6} />)
    expect(sceneryBox()!.checked).toBe(true)
    expect(screen.getByTestId('record-scenery-row').textContent).toContain('the look keeps every held piece')
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Night' } })
    fireEvent.click(screen.getByRole('button', { name: 'Record' }))
    await waitFor(() => expect(recordLook).toHaveBeenCalled())
    expect(recordLook.mock.calls[0][0]).toMatchObject({ mode: 'CREATE', name: 'Night', scenery: true })
    expect(await screen.findByText('1 scenery change')).toBeTruthy()
  })
})

describe('describeSceneryWrite', () => {
  it('says only what happened', () => {
    expect(describeSceneryWrite()).toEqual([])
    expect(describeSceneryWrite(1, 0, 2)).toEqual(['1 scenery change', '2 held pieces already shown there'])
    expect(describeSceneryWrite(0, 3)).toEqual(['3 scenery rows removed'])
  })
})
