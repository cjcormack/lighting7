import { describe, expect, it } from 'vitest'
import type { TargetColours } from '@/lib/targetColours'
import { bindingTargetColour } from './targetColour'

const colours: TargetColours = {
  fixtures: new Map([['par-1', 'red'], ['bar.pixel-0', 'cyan']]),
  groups: new Map([['Movers', 'lime']]),
}

describe('a binding target’s colour', () => {
  it('is the fixture’s, the head’s fixture’s, or the group’s, through every target that names one', () => {
    expect(bindingTargetColour({ type: 'fixtureProperty', fixtureKey: 'par-1', propertyName: 'dimmer' }, colours)).toBe('red')
    expect(bindingTargetColour({ type: 'groupProperty', groupName: 'Movers', propertyName: 'dimmer' }, colours)).toBe('lime')
    expect(
      bindingTargetColour(
        { type: 'flash', target: { type: 'fixtureProperty', fixtureKey: 'bar.pixel-0', propertyName: 'dimmer' } },
        colours,
      ),
    ).toBe('cyan')
    expect(bindingTargetColour({ type: 'selectTarget', target: { type: 'group', key: 'Movers' }, mode: 'toggle' }, colours)).toBe('lime')
    expect(bindingTargetColour({ type: 'strip', target: { type: 'fixture', key: 'par-1' } }, colours)).toBe('red')
  })

  it('is none for a target that names no fixture, or one with no colour', () => {
    expect(bindingTargetColour({ type: 'blackout' }, colours)).toBeNull()
    expect(bindingTargetColour({ type: 'fixtureProperty', fixtureKey: 'par-9', propertyName: 'dimmer' }, colours)).toBeNull()
  })
})
