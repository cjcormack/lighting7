import { describe, expect, it } from 'vitest'
import { SEATED_EYE_HEIGHT_M, parseSeatId, seatBase, seatEye, seatList, seatingParams } from './stageSeats'
import type { StageElementDto } from '../api/stageElementApi'

const stalls = {
  kind: 'SEATING' as const,
  positionX: 0,
  positionY: -2.4,
  positionZ: -0.95,
  yawDeg: 0,
  params: { rows: 12, seatsPerRow: 12, rowPitchM: 0.95, seatPitchM: 0.52 },
} satisfies Pick<StageElementDto, 'kind' | 'positionX' | 'positionY' | 'positionZ' | 'yawDeg' | 'params'>

describe('the seats of a seating element', () => {
  // The backend's StageSceneTest pins the same seats.
  it('runs rows away from the stage from row A, seat 1 at the stage-right end', () => {
    const params = seatingParams(stalls)!
    expect(params.firstRow).toBe('A')
    const a1 = seatBase(stalls, params, 'A1')!
    expect(a1.x).toBeCloseTo(-2.86, 9)
    expect(a1.y).toBeCloseTo(-2.4, 9)
    const f6 = seatBase(stalls, params, 'f6')!
    expect(f6.x).toBeCloseTo(-0.26, 9)
    expect(f6.y).toBeCloseTo(-2.4 - 5 * 0.95, 9)
    const eye = seatEye(stalls, f6)
    expect(eye.y).toBeCloseTo(f6.y - 0.05, 9)
    expect(eye.z).toBeCloseTo(-0.95 + SEATED_EYE_HEIGHT_M, 9)
    expect(seatBase(stalls, params, 'M1')).toBeNull()
    expect(seatBase(stalls, params, 'A13')).toBeNull()
  })

  it('turns the block about its origin and lifts each row by the rake', () => {
    const turned = { ...stalls, positionX: 1, positionY: -2, positionZ: 0, yawDeg: 90, params: { rows: 3, seatsPerRow: 1, rowPitchM: 1, seatPitchM: 0.5, rakeM: 0.2 } }
    const c1 = seatBase(turned, seatingParams(turned)!, 'C1')!
    expect(c1.x).toBeCloseTo(3, 9)
    expect(c1.y).toBeCloseTo(-2, 9)
    expect(c1.z).toBeCloseTo(0.4, 9)
  })

  // The backend's StageSceneTest pins the same seats: six banquet chairs a side of a centre aisle.
  it('opens an aisle in every row without renumbering a seat, the row still centred', () => {
    const hall = {
      ...stalls,
      positionY: -2.65,
      params: { rows: 17, seatsPerRow: 12, rowPitchM: 0.85, seatPitchM: 0.5, chair: 'BANQUET', aisles: [{ afterSeat: 6, widthM: 1.1 }] },
    }
    const params = seatingParams(hall)!
    expect(params.chair).toBe('BANQUET')
    expect(seatBase(hall, params, 'A1')!.x).toBeCloseTo(-3.3, 9)
    expect(seatBase(hall, params, 'A6')!.x).toBeCloseTo(-0.8, 9)
    expect(seatBase(hall, params, 'A7')!.x).toBeCloseTo(0.8, 9)
    const q12 = seatBase(hall, params, 'Q12')!
    expect(q12.x).toBeCloseTo(3.3, 9)
    expect(q12.y).toBeCloseTo(-2.65 - 16 * 0.85, 9)
    expect(seatList(hall, params)).toHaveLength(204)
  })

  it('narrows what it reads rather than casting', () => {
    expect(seatingParams({ kind: 'OBJECT', params: stalls.params })).toBeNull()
    expect(seatingParams({ kind: 'SEATING', params: { rows: 'twelve' } })).toBeNull()
    const odd = seatingParams({ kind: 'SEATING', params: { ...stalls.params, chair: 'pew', frameColour: 'gold', aisles: [{ afterSeat: 'six' }, null] } })!
    expect(odd.chair).toBe('THEATRE')
    expect(odd.frameColour).toBeNull()
    expect(odd.aisles).toEqual([])
    expect(parseSeatId('6F')).toBeNull()
    expect(parseSeatId(' b12 ')).toEqual({ row: 'B', number: 12 })
  })

  it('lists every seat, row A first and seat 1 first, each exactly its own id\'s base', () => {
    const params = seatingParams(stalls)!
    const seats = seatList(stalls, params)
    expect(seats).toHaveLength(144)
    expect(seats[0].id).toBe('A1')
    expect(seats[11].id).toBe('A12')
    expect(seats[12].id).toBe('B1')
    expect(seats[143].id).toBe('L12')
    for (const seat of seats) expect(seat.base).toEqual(seatBase(stalls, params, seat.id))
    const f6 = seats.find((s) => s.id === 'F6')!
    expect(f6.row).toBe('F')
    expect(f6.number).toBe(6)
  })
})
