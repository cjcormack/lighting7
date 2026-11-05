// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { palettePressTitle, tapVerb } from './PressGestures'
import type { PaletteSummary } from '@/api/palettesApi'

const NIGHT = { kind: 'look', lookName: 'Night' } as const

describe('tapVerb in a focused Look (palette-references plan D23)', () => {
  it('puts a value palette’s references on or takes them off, and adds an FX palette', () => {
    expect(tapVerb(NIGHT, 'none')).toEqual({ label: 'Put on or take off in Night', gesture: 'press' })
    expect(tapVerb(NIGHT, 'none', true, true, false, true)).toEqual({ label: 'Into Night', gesture: 'press' })
  })
})

describe('palettePressTitle in a focused Look', () => {
  const amber = { name: 'Amber', kind: 'value' } as PaletteSummary
  it('says a tap is references and ⌥click the literals', () => {
    expect(palettePressTitle(amber, NIGHT)).toBe(
      'Click to put “Amber” on in Night as references to it, or take it off · ⌥click to set its values as Night’s own · hold or right-click for more',
    )
    expect(palettePressTitle({ ...amber, kind: 'fx' }, NIGHT)).toBe('Click to add “Amber” to Night · hold or right-click for more')
  })
})
