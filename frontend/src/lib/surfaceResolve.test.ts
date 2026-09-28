import { describe, it, expect } from 'vitest'
import {
  activeBindingAt,
  buildBindingIndex,
  deriveStripTarget,
  exactBindingAt,
  resolveControl,
  stripControlsByControlId,
} from './surfaceResolve'
import type {
  BindingTarget,
  ControlSurfaceBinding,
  StripDefinition,
} from '../api/surfacesApi'
import type { ColourAxis, EncoderBankSelection } from '../api/surfacesApi'

/**
 * The client mirror of `ControlSurfaceBindingService.resolve` and `deriveStripTarget`, pinned
 * against the Kotlin cases it copies — `ControlSurfaceBindingResolverTest` and `StripDeriveTest`
 * in lighting7. There is no way to reach the backend's answer from a browser, so a divergence
 * here shows up only as a panel that labels a control differently from the desk that drives it.
 */

const KEY = 'x-touch-compact-standard'

const group = { type: 'group', key: 'front-wash' } as const
const fixture = { type: 'fixture', key: 'hex-1' } as const

const strip1: StripDefinition = {
  id: 'strip-1',
  fader: 'fader-1',
  select: 'btn-25',
  encoder: 'enc-1',
  flash: 'btn-1',
}
const stripMaster: StripDefinition = {
  id: 'strip-master',
  fader: 'fader-9',
  select: 'btn-33',
  encoder: null,
  flash: null,
}
const profile = { typeKey: KEY, strips: [strip1, stripMaster] }

function binding(
  id: number,
  controlId: string,
  target: BindingTarget,
  bank: string | null = null,
  health: ControlSurfaceBinding['health'] = { type: 'ok' },
): ControlSurfaceBinding {
  return {
    id,
    projectId: 1,
    deviceTypeKey: KEY,
    controlId,
    bank,
    target,
    targetType: target.type,
    takeoverPolicy: null,
    sortOrder: id,
    health,
  }
}

const index = (bindings: ControlSurfaceBinding[]) => buildBindingIndex(bindings, profile)

/** An encoder bank selection, `colourAxis` absent for hue — the form the server sends. */
const bank = (propertyName: string, colourAxis?: ColourAxis): EncoderBankSelection =>
  colourAxis ? { propertyName, colourAxis } : { propertyName }

describe('deriveStripTarget', () => {
  it('drives dimmer from the fader and the flash, whatever the encoder bank', () => {
    expect(deriveStripTarget('fader', group, bank('pan'))).toEqual({
      type: 'groupProperty',
      groupName: 'front-wash',
      propertyName: 'dimmer',
    })
    expect(deriveStripTarget('flash', group, bank('pan'))).toEqual({
      type: 'flash',
      target: { type: 'groupProperty', groupName: 'front-wash', propertyName: 'dimmer' },
    })
  })

  it('toggles rather than replaces on the select button', () => {
    expect(deriveStripTarget('select', group, bank('dimmer'))).toEqual({
      type: 'selectTarget',
      target: group,
      mode: 'toggle',
    })
  })

  it('follows the encoder bank on the encoder', () => {
    expect(deriveStripTarget('encoder', group, bank('dimmer'))).toMatchObject({ propertyName: 'dimmer' })
    expect(deriveStripTarget('encoder', group, bank('colour'))).toMatchObject({ propertyName: 'colour' })
  })

  it('carries the bank’s colour axis on the encoder, and never on the fader or flash', () => {
    // Replays lighting7's StripDeriveTest: the strip encoder is the one control a colour axis
    // reaches on a strip. `toEqual` is the point of the shape: a hue bank derives a target with
    // the field *absent*, which is what every pre-axis row has.
    const sat = bank('rgbColour', 'saturation')
    expect(deriveStripTarget('encoder', group, sat)).toEqual({
      type: 'groupProperty',
      groupName: 'front-wash',
      propertyName: 'rgbColour',
      colourAxis: 'saturation',
    })
    expect(deriveStripTarget('encoder', fixture, sat)).toEqual({
      type: 'fixtureProperty',
      fixtureKey: 'hex-1',
      propertyName: 'rgbColour',
      colourAxis: 'saturation',
    })
    expect(deriveStripTarget('fader', group, sat)).toEqual({
      type: 'groupProperty',
      groupName: 'front-wash',
      propertyName: 'dimmer',
    })
    expect(deriveStripTarget('flash', group, sat)).toEqual({
      type: 'flash',
      target: { type: 'groupProperty', groupName: 'front-wash', propertyName: 'dimmer' },
    })
    expect(deriveStripTarget('encoder', group, bank('rgbColour'))).toEqual({
      type: 'groupProperty',
      groupName: 'front-wash',
      propertyName: 'rgbColour',
    })
    expect(deriveStripTarget('encoder', group, { propertyName: 'rgbColour', colourAxis: 'hue' })).toEqual(
      { type: 'groupProperty', groupName: 'front-wash', propertyName: 'rgbColour' },
    )
  })

  it('derives fixture targets from a fixture target', () => {
    expect(deriveStripTarget('fader', fixture, bank('tilt'))).toEqual({
      type: 'fixtureProperty',
      fixtureKey: 'hex-1',
      propertyName: 'dimmer',
    })
    expect(deriveStripTarget('encoder', fixture, bank('tilt'))).toEqual({
      type: 'fixtureProperty',
      fixtureKey: 'hex-1',
      propertyName: 'tilt',
    })
  })
})

describe('stripControlsByControlId', () => {
  it('claims every control of a full strip, by role', () => {
    const byControl = stripControlsByControlId([strip1])
    expect(byControl.get('fader-1')?.role).toBe('fader')
    expect(byControl.get('btn-25')?.role).toBe('select')
    expect(byControl.get('enc-1')?.role).toBe('encoder')
    expect(byControl.get('btn-1')?.role).toBe('flash')
    expect(byControl.get('fader-2')).toBeUndefined()
  })

  it('claims nothing for a role the master strip does not declare', () => {
    const byControl = stripControlsByControlId([stripMaster])
    expect([...byControl.keys()].sort()).toEqual(['btn-33', 'fader-9'])
  })
})

describe('resolveControl', () => {
  it('answers null when nothing is bound', () => {
    expect(resolveControl('fader-1', index([]), null, bank('dimmer'))).toBeNull()
  })

  it('prefers the exact bank to the bank-agnostic row on one control', () => {
    const i = index([
      binding(1, 'fader-1', { type: 'blackout' }, null),
      binding(2, 'fader-1', { type: 'grandMasterToggle' }, 'layer-a'),
    ])
    expect(resolveControl('fader-1', i, 'layer-a', bank('dimmer'))?.binding.id).toBe(2)
    expect(resolveControl('fader-1', i, 'layer-b', bank('dimmer'))?.binding.id).toBe(1)
  })

  it('resolves each of a strip’s controls to that control’s role', () => {
    const i = index([binding(9, 'strip-1', { type: 'strip', target: group })])
    expect(resolveControl('fader-1', i, null, bank('colour'))?.target).toEqual({
      type: 'groupProperty',
      groupName: 'front-wash',
      propertyName: 'dimmer',
    })
    expect(resolveControl('enc-1', i, null, bank('colour'))?.target).toEqual({
      type: 'groupProperty',
      groupName: 'front-wash',
      propertyName: 'colour',
    })
    expect(resolveControl('btn-25', i, null, bank('colour'))?.target).toEqual({
      type: 'selectTarget',
      target: group,
      mode: 'toggle',
    })
    expect(resolveControl('btn-1', i, null, bank('colour'))?.target).toMatchObject({ type: 'flash' })
  })

  it('lets a control’s own binding beat its strip, leaving the siblings on the strip', () => {
    const i = index([
      binding(9, 'strip-1', { type: 'strip', target: group }),
      binding(10, 'fader-1', { type: 'blackout' }),
    ])
    expect(resolveControl('fader-1', i, null, bank('dimmer'))?.binding.id).toBe(10)
    expect(resolveControl('enc-1', i, null, bank('dimmer'))?.binding.id).toBe(9)
  })

  // The load-bearing case: direct-first is applied across *both* bank levels before the strip is
  // consulted, so a bank-agnostic single binding wins even against a strip bound to the active
  // bank. A bank-first reading of the same four rows answers 9 here, and nothing on the screen
  // would look wrong.
  it('lets a bank-agnostic direct binding beat an exact-bank strip', () => {
    const i = index([
      binding(9, 'strip-1', { type: 'strip', target: group }, 'layer-a'),
      binding(10, 'fader-1', { type: 'blackout' }, null),
    ])
    expect(resolveControl('fader-1', i, 'layer-a', bank('dimmer'))?.binding.id).toBe(10)
  })

  it('lets an exact-bank strip beat a bank-agnostic strip', () => {
    const movers = { type: 'group', key: 'movers' } as const
    const i = index([
      binding(9, 'strip-1', { type: 'strip', target: group }, null),
      binding(11, 'strip-1', { type: 'strip', target: movers }, 'layer-a'),
    ])
    expect(resolveControl('fader-1', i, 'layer-a', bank('dimmer'))?.target).toMatchObject({
      groupName: 'movers',
    })
    expect(resolveControl('fader-1', i, 'layer-b', bank('dimmer'))?.target).toMatchObject({
      groupName: 'front-wash',
    })
  })

  it('carries the strip row’s health to every derived control', () => {
    const i = index([
      binding(9, 'strip-1', { type: 'strip', target: group }, null, {
        type: 'missingGroup',
        groupName: 'front-wash',
      }),
    ])
    expect(resolveControl('enc-1', i, null, bank('dimmer'))?.binding.health).toEqual({
      type: 'missingGroup',
      groupName: 'front-wash',
    })
  })

  it('resolves nothing for a control no strip claims', () => {
    const i = index([binding(9, 'strip-master', { type: 'strip', target: group })])
    expect(resolveControl('fader-9', i, null, bank('dimmer'))).not.toBeNull()
    expect(resolveControl('enc-9', i, null, bank('dimmer'))).toBeNull()
  })

  // Only reachable for a row this build cannot decode, which the tolerant decode keeps rather than
  // dropping. It must still surface — dead and rebindable — rather than reading as unbound.
  it('answers a strip slot holding something other than a strip as it stands', () => {
    const i = index([
      binding(
        9,
        'strip-1',
        { type: 'unknown', targetType: 'fromTheFuture', rawPayload: '{}' },
        null,
        { type: 'unknownTarget', targetType: 'fromTheFuture' },
      ),
    ])
    const hit = resolveControl('fader-1', i, null, bank('dimmer'))
    expect(hit?.target).toMatchObject({ type: 'unknown' })
    expect(hit?.binding.health).toEqual({ type: 'unknownTarget', targetType: 'fromTheFuture' })
  })

  it('ignores rows belonging to another device type', () => {
    const other = { ...binding(1, 'fader-1', { type: 'blackout' }), deviceTypeKey: 'other-device' }
    expect(resolveControl('fader-1', index([other]), null, bank('dimmer'))).toBeNull()
  })
})

// A desk running a build from before strips serves neither `strips` nor `layout`, and this client
// talks to whatever desk it is pointed at. Every reader has to stand up to that rather than
// throwing on the first dereference.
describe('a profile from a pre-strip desk', () => {
  it('resolves direct bindings and finds no strips', () => {
    const older = { typeKey: KEY } as { typeKey: string; strips?: StripDefinition[] }
    const i = buildBindingIndex([binding(1, 'fader-1', { type: 'blackout' })], older)
    expect(resolveControl('fader-1', i, null, bank('dimmer'))?.binding.id).toBe(1)
    expect(resolveControl('btn-25', i, null, bank('dimmer'))).toBeNull()
  })
})

/**
 * The two slot lookups, which differ by exactly one fallback and must not be confused.
 *
 * A *write* replaces the row at the bank it is aimed at and nothing else, or retargeting a control
 * on bank A would silently retarget it on every other bank. A *read* — the label under a control,
 * the cross that removes it — has to see the bank-agnostic row, because that row really is driving
 * the slot on every bank.
 */
describe('exactBindingAt vs activeBindingAt', () => {
  const globalRow = binding(1, 'fader-1', { type: 'blackout' }, null)
  const bankRow = binding(2, 'fader-1', { type: 'grandMasterToggle' }, 'layer-b')
  const i = index([globalRow, bankRow])

  it('agree where an exact-bank row exists', () => {
    expect(exactBindingAt(i, 'fader-1', 'layer-b')?.id).toBe(2)
    expect(activeBindingAt(i, 'fader-1', 'layer-b')?.id).toBe(2)
  })

  it('disagree where only the bank-agnostic row does', () => {
    expect(exactBindingAt(i, 'fader-1', 'layer-a')).toBeNull()
    expect(activeBindingAt(i, 'fader-1', 'layer-a')?.id).toBe(1)
  })

  it('both answer null for a slot with nothing on it', () => {
    expect(exactBindingAt(i, 'btn-1', null)).toBeNull()
    expect(activeBindingAt(i, 'btn-1', null)).toBeNull()
  })
})
