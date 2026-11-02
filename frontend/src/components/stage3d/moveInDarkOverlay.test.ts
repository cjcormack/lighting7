import { describe, expect, it } from 'vitest'
import { Group, Matrix4, Vector3 } from 'three'
import { parseMoveInDarkFrame } from '../../api/moveInDarkApi'
import { VIS_SOURCES } from '../../hooks/useVisSource'
import { drawsMovesInDark, markBadge, markCounting, markFor, markRingRadiusM, moveInDarkMarks, type MoveInDarkMark } from './moveInDarkOverlay'
import { markTargetMatrix } from './MoveInDarkMarker'

const frame = parseMoveInDarkFrame(
  {
    type: 'moveInDark.state',
    projectId: 3,
    stacks: [
      {
        stackId: 1,
        heads: [
          { target: 'spot-1', entryId: 12, cueId: 40, label: 'Q12', state: 'SET', families: ['POSITION'], position: { pan: 142, tilt: 88 } },
          { target: 'spot-3', entryId: 12, cueId: 40, label: 'Q12', state: 'MOVING', remainingMs: 1400, families: ['POSITION'], position: { pan: 60, tilt: 90 } },
          { target: 'spot-4', entryId: 12, cueId: 40, label: 'Q12', state: 'LIT_EARLY', families: ['POSITION'], position: { pan: 1, tilt: 2 } },
          { target: 'wash-1', entryId: 12, cueId: 40, label: 'Q12', state: 'SET', families: ['COLOUR'] },
        ],
      },
      {
        stackId: 2,
        heads: [{ target: 'spot-1', entryId: 30, cueId: 50, label: 'Q30', state: 'WAITING', remainingMs: 600, families: ['POSITION'], position: { pan: 9, tilt: 9 } }],
      },
    ],
  },
  10_000,
)

describe('the Stage view’s Move in dark overlay', () => {
  it('is drawn in the two Output sources with the switch on, and nowhere else', () => {
    for (const source of VIS_SOURCES) {
      expect(drawsMovesInDark(source, 'on')).toBe(source === 'output' || source === 'outputProgrammer')
      expect(drawsMovesInDark(source, 'off')).toBe(false)
    }
  })

  it('marks each head the live show moves with a position, leaving out a head lit early and one with no position', () => {
    const marks = moveInDarkMarks(frame, 3)
    expect([...marks.keys()]).toEqual(['spot-1', 'spot-3'])
    expect(marks.get('spot-1')).toEqual({ label: 'Q12', state: 'SET', endsAtMs: null, pan: 142, tilt: 88 })
    expect(marks.get('spot-3')).toMatchObject({ state: 'MOVING', endsAtMs: 11_400, pan: 60, tilt: 90 })
  })

  it('marks nothing for another project', () => {
    expect(moveInDarkMarks(frame, 4).size).toBe(0)
  })

  it('hands no mark to a capture', () => {
    const marks = moveInDarkMarks(frame, 3)
    expect(markFor(marks, 'spot-1', false)).not.toBeNull()
    expect(markFor(marks, 'spot-1', true)).toBeNull()
    expect(markFor(null, 'spot-1', false)).toBeNull()
  })

  it('badges the cue, and the time left while it moves or waits', () => {
    const base: MoveInDarkMark = { label: 'Q12', state: 'SET', endsAtMs: null, pan: 0, tilt: 0 }
    expect(markBadge(base, 0)).toBe('☾ Q12')
    expect(markBadge({ ...base, state: 'MOVING', endsAtMs: 1400 }, 0)).toBe('☾ Q12 · 1.4s')
    expect(markBadge({ ...base, state: 'WAITING', endsAtMs: 600 }, 0)).toBe('☾ Q12 · in 0.6s')
    expect(markBadge({ ...base, state: 'MOVING', endsAtMs: 1400 }, 2000)).toBe('☾ Q12')
    expect(markCounting({ ...base, state: 'MOVING', endsAtMs: 1400 }, 0)).toBe(true)
    expect(markCounting({ ...base, state: 'MOVING', endsAtMs: 1400 }, 2000)).toBe(false)
    expect(markCounting(base, 0)).toBe(false)
  })

  it('sizes the ring as the pool at that throw, within reason', () => {
    expect(markRingRadiusM(8, 20)).toBeCloseTo(8 * Math.tan((10 * Math.PI) / 180), 6)
    expect(markRingRadiusM(0.1, 20)).toBe(0.15)
    expect(markRingRadiusM(100, 60)).toBe(1.5)
  })

  it('aims the target where the beam director would turn the head', () => {
    // The director's own rig: a mount, a yoke that pans and a head that tilts at its pivot.
    const mount = new Group()
    mount.position.set(1, 5, -2)
    mount.rotation.set(Math.PI, 0.3, 0, 'YXZ')
    const yoke = new Group()
    const head = new Group()
    head.position.set(0, 0.2, 0)
    mount.add(yoke)
    yoke.add(head)
    yoke.rotation.set(0, 0.7, 0)
    head.rotation.set(-0.4, 0, 0)
    mount.updateMatrixWorld(true)
    const out = markTargetMatrix(mount.matrixWorld, head.position, 0.7, -0.4, new Matrix4())
    const expected = head.matrixWorld.elements
    out.elements.forEach((v, i) => expect(v).toBeCloseTo(expected[i], 9))
    const dir = new Vector3(0, -1, 0).transformDirection(out)
    expect(dir.length()).toBeCloseTo(1, 9)
  })
})
