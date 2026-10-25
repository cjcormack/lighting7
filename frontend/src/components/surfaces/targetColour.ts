import type { BindingTarget } from '@/api/surfacesApi'
import { targetColour, type TargetColours } from '@/lib/targetColours'
import type { FixtureColour } from '@/lib/fixtureColours'

/**
 * The colour of the fixture or group a binding drives, for the label under its control
 * (tables-colours plan D8): a property, a flash, a select, a strip or a trigger names one; every
 * other target — a stack, the selection, a page — names no fixture and draws none.
 */
export function bindingTargetColour(target: BindingTarget, colours: TargetColours): FixtureColour | null {
  switch (target.type) {
    case 'fixtureAttribute':
    case 'fireTrigger':
      return targetColour({ type: 'fixture', key: target.fixtureKey }, colours)
    case 'groupAttribute':
      return targetColour({ type: 'group', key: target.groupName }, colours)
    case 'flash':
      return bindingTargetColour(target.target, colours)
    case 'selectTarget':
    case 'strip':
      return targetColour(target.target, colours)
    default:
      return null
  }
}
