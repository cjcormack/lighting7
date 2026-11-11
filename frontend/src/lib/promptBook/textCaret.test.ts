import { describe, expect, it } from 'vitest'
import { nearestRunPosition, type TextRun } from './textCaret'

const run = (left: number, top: number, right: number, length: number): TextRun => ({
  left,
  top,
  right,
  bottom: top + 10,
  length,
})

describe('nearestRunPosition', () => {
  const lines = [run(100, 0, 200, 10), run(100, 20, 150, 5), run(100, 40, 300, 20)]

  it('takes the start of the line when the press is in its left margin', () => {
    expect(nearestRunPosition(lines, 40, 25)).toEqual({ index: 1, offset: 0 })
  })

  it('takes the end of the line when the press is past it', () => {
    expect(nearestRunPosition(lines, 400, 25)).toEqual({ index: 1, offset: 5 })
  })

  it('takes the nearer line when the press is between two', () => {
    expect(nearestRunPosition(lines, 120, 13)).toEqual({ index: 0, offset: 2 })
    expect(nearestRunPosition(lines, 120, 18)).toEqual({ index: 1, offset: 2 })
  })

  it('prefers the run nearest across on a line of several', () => {
    const line = [run(100, 0, 140, 4), run(200, 0, 260, 6)]
    expect(nearestRunPosition(line, 190, 5)).toEqual({ index: 1, offset: 0 })
    expect(nearestRunPosition(line, 150, 5)).toEqual({ index: 0, offset: 4 })
  })

  it('places the caret in proportion inside a run', () => {
    expect(nearestRunPosition(lines, 200, 45)).toEqual({ index: 2, offset: 10 })
  })

  it('skips empty runs and answers null without text', () => {
    expect(nearestRunPosition([run(0, 0, 50, 0), run(0, 100, 50, 5)], 10, 5)).toEqual({ index: 1, offset: 1 })
    expect(nearestRunPosition([], 10, 5)).toBeNull()
  })
})
