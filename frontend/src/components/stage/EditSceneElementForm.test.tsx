// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { StageElementDto } from '@/api/stageElementApi'

const update = vi.fn()
const remove = vi.fn()
vi.mock('@/store/stageElements', () => ({
  useUpdateStageElementMutation: () => [update, { isLoading: false }],
  useDeleteStageElementMutation: () => [remove, { isLoading: false }],
}))
vi.mock('@/store/stageRegions', () => ({
  useStageRegionListQuery: () => ({ data: [{ uuid: 'r-1', name: 'Main stage' }] }),
}))
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

import { EditSceneElementForm } from './EditSceneElementForm'

function element(over: Partial<StageElementDto> = {}): StageElementDto {
  return {
    id: 7,
    uuid: 'flat-1',
    name: 'Flat 1',
    kind: 'FLAT',
    layer: 'SET',
    positionX: 1,
    positionY: 7,
    positionZ: 0,
    yawDeg: 0,
    widthM: 2.4,
    depthM: 0.1,
    heightM: 2.4,
    finishColour: null,
    finishPattern: null,
    emissive: false,
    params: { openings: [{ kind: 'DOOR', fromM: 0.2, widthM: 0.9, heightM: 2.1 }] },
    hidden: false,
    sortOrder: 0,
    ...over,
  }
}

/** The mutation's `unwrap()` rejecting as RTK Query does: `{status, data: {error, code}}`. */
function refuse(status: number, error: string, code?: string) {
  return { unwrap: () => Promise.reject({ status, data: { error, ...(code && { code }) } }) }
}

function type(label: string | RegExp, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
}

beforeEach(() => {
  update.mockReset()
  remove.mockReset()
})
afterEach(cleanup)

describe('EditSceneElementForm (stage-view plan session 5)', () => {
  it('draws every problem of a 400 beside the field it names, and the rest at the top', async () => {
    update.mockReturnValue(
      refuse(
        400,
        [
          'heightM must be greater than 0: every kind but SEATING has a size',
          "params.openings[0] runs past the flat's end (fromM + widthM = 3.2, widthM 2.4)",
          "params: unknown field 'wobble' (known: openings, states)",
        ].join('; '),
      ),
    )
    const onClose = vi.fn()
    render(<EditSceneElementForm element={element()} projectId={3} onClose={onClose} />)
    fireEvent.change(document.getElementById('element-h')!, { target: { value: '0' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    const height = await screen.findByText('Must be greater than 0: every kind but SEATING has a size')
    // Beside the Height field, not in a toast.
    expect(height.closest('div.space-y-1')!.querySelector('#element-h')).not.toBeNull()
    const opening = screen.getByText("Runs past the flat's end (fromM + widthM = 3.2, widthM 2.4)")
    expect(opening.closest('[data-opening="0"]')).not.toBeNull()
    // One naming no field this form draws is the form's own, at the top.
    expect(within(screen.getAllByRole('alert')[0]!).getByText("params: unknown field 'wobble' (known: openings, states)")).toBeTruthy()
    expect(onClose).not.toHaveBeenCalled()
    expect(update).toHaveBeenCalledWith({ projectId: 3, elementId: 7, force: false, heightM: 0 })
  })

  it('refuses an emptied number beside its field and sends nothing', async () => {
    render(<EditSceneElementForm element={element()} projectId={3} onClose={() => {}} />)
    type('X', '')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    const note = await screen.findByText('Enter a number')
    expect(note.closest('div.space-y-1')!.querySelector('#element-x')).not.toBeNull()
    expect(update).not.toHaveBeenCalled()
  })

  it('saves what changed, params whole, and closes', async () => {
    update.mockReturnValue({ unwrap: () => Promise.resolve(element()) })
    const onClose = vi.fn()
    render(<EditSceneElementForm element={element()} projectId={3} onClose={onClose} />)
    type('Yaw (deg)', '15')
    fireEvent.click(screen.getByRole('button', { name: 'Remove opening 1' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(update).toHaveBeenCalledWith({ projectId: 3, elementId: 7, force: false, yawDeg: 15, params: { openings: [] } })
  })

  it('a platform edits its rail, its deck region and a flat’s kind fields are not drawn', async () => {
    update.mockReturnValue({ unwrap: () => Promise.resolve(element()) })
    const platform = element({ kind: 'PLATFORM', name: 'Rostrum', positionZ: 0.4, heightM: 0.4, params: {} })
    render(<EditSceneElementForm element={platform} projectId={3} onClose={() => {}} />)
    expect(screen.getByLabelText('Z (top)')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Opening/ })).toBeNull()
    type('Rail height', '1')
    fireEvent.change(screen.getByLabelText('Rail on'), { target: { value: 'DOWNSTAGE' } })
    fireEvent.change(screen.getByLabelText('Deck of region'), { target: { value: 'r-1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0]![0].params).toEqual({ railHeightM: 1, railEdge: 'DOWNSTAGE', regionUuid: 'r-1' })
  })

  it('asks before reshaping a seating that seat views sit in, and forces only when told to', async () => {
    const message = "Seat views still look from this seating: 'Row F centre'. Send ?force=true to go ahead and leave them without a seat."
    update.mockReturnValueOnce(refuse(409, message, 'STAGE_ELEMENT_IN_USE'))
    update.mockReturnValueOnce({ unwrap: () => Promise.resolve(element()) })
    const stalls = element({
      kind: 'SEATING',
      name: 'Stalls',
      widthM: 0,
      depthM: 0,
      heightM: 0,
      params: { rows: 10, seatsPerRow: 12, rowPitchM: 0.9, seatPitchM: 0.5 },
    })
    render(<EditSceneElementForm element={stalls} projectId={3} onClose={() => {}} />)
    // A seating's size is its rows and seats.
    expect(screen.queryByLabelText('Width')).toBeNull()
    type('Rows', '4')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText(message)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Save anyway' }))
    await waitFor(() => expect(update).toHaveBeenCalledTimes(2))
    expect(update.mock.calls[1]![0]).toMatchObject({ force: true, params: { rows: 4 } })
  })

  it('a seating picks its chair and frame, and adds an aisle whose problems sit beside it', async () => {
    update.mockReturnValueOnce({ unwrap: () => Promise.resolve(element()) })
    update.mockReturnValueOnce(refuse(400, "params.aisles[0].afterSeat (12) must be before the row's last seat (12)"))
    const stalls = element({
      kind: 'SEATING',
      name: 'Stalls',
      widthM: 0,
      depthM: 0,
      heightM: 0,
      params: { rows: 10, seatsPerRow: 12, rowPitchM: 0.9, seatPitchM: 0.5 },
    })
    render(<EditSceneElementForm element={stalls} projectId={3} onClose={() => {}} />)
    expect((screen.getByLabelText('Chair') as HTMLSelectElement).value).toBe('THEATRE')
    fireEvent.change(screen.getByLabelText('Chair'), { target: { value: 'BANQUET' } })
    type('Frame colour', '#c9a44c')
    fireEvent.click(screen.getByRole('button', { name: 'Aisle' }))
    expect((screen.getByLabelText('Aisle after seat') as HTMLInputElement).value).toBe('6')
    type('Aisle width', '1.1')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    expect(update.mock.calls[0]![0].params).toEqual({
      rows: 10,
      seatsPerRow: 12,
      rowPitchM: 0.9,
      seatPitchM: 0.5,
      chair: 'BANQUET',
      frameColour: '#c9a44c',
      aisles: [{ afterSeat: 6, widthM: 1.1 }],
    })

    type('Aisle after seat', '12')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    const problem = await screen.findByText("(12) must be before the row's last seat (12)")
    expect(problem.closest('[data-aisle="0"]')).not.toBeNull()
  })

  it('adds each aisle at a free gap nearest the middle, and none where a row has no gap', () => {
    const seating = (seatsPerRow: number) =>
      element({ kind: 'SEATING', name: 'Stalls', widthM: 0, depthM: 0, heightM: 0, params: { rows: 2, seatsPerRow, rowPitchM: 0.9, seatPitchM: 0.5 } })
    render(<EditSceneElementForm element={seating(12)} projectId={3} onClose={() => {}} />)
    const add = screen.getByRole('button', { name: 'Aisle' })
    fireEvent.click(add)
    fireEvent.click(add)
    fireEvent.click(add)
    const seats = screen.getAllByLabelText('Aisle after seat').map((el) => (el as HTMLInputElement).value)
    expect(seats).toEqual(['6', '5', '7'])
    cleanup()

    render(<EditSceneElementForm element={seating(1)} projectId={3} onClose={() => {}} />)
    expect((screen.getByRole('button', { name: 'Aisle' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('lists what moves it, read-only — nothing until cues, stacks and Looks can', () => {
    render(<EditSceneElementForm element={element()} projectId={3} onClose={() => {}} />)
    expect(screen.getByText('Moves with')).toBeTruthy()
    expect(document.querySelector('[data-moves-with]')!.textContent).toMatch(/No cue, stack or Look moves it/)
  })

  it('shows where a drag on a section has moved it, without saving', () => {
    const ref = { current: null as null | { setPosition: (p: { positionX: number; positionY: number; positionZ: number }) => void } }
    render(<EditSceneElementForm ref={ref} element={element()} projectId={3} onClose={() => {}} />)
    ref.current!.setPosition({ positionX: 2.5, positionY: 6, positionZ: 0 })
    return waitFor(() => expect((screen.getByLabelText('X') as HTMLInputElement).value).toBe('2.5'))
  })
})
