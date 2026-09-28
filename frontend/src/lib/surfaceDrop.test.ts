import { describe, expect, it } from 'vitest'
import {
  bindingWriteFor,
  canLand,
  controlKinds,
  targetControlKind,
  type SurfaceChipDrag,
  type SurfaceControlDrop,
  type SurfaceDropData,
  type SurfaceRowDrag,
} from './surfaceDrop'
import { buildBindingIndex } from './surfaceResolve'
import type {
  BindingTarget,
  ControlDescriptor,
  ControlSurfaceBinding,
  ControlSurfaceType,
} from '@/api/surfacesApi'

/**
 * The drag rules and the one write a drop makes.
 *
 * Pure on purpose: jsdom gives every rect zero width, so a mapping only reachable through a pointer
 * sequence is a mapping nothing checks — `dnd/slotDrop.ts` is tested the same way for the same
 * reason. What is asserted here is what the operator cannot see going wrong: a chip that saves onto
 * a control the router will never dispatch, and a drop that patches a binding they were not
 * pointing at.
 */

const fader: ControlDescriptor = {
  type: 'fader',
  controlId: 'fader-1',
  label: 'Fader 1',
  cc: 1,
  channel: 1,
  hasMotor: true,
  motorCc: 1,
  touchNote: null,
  touchCc: 101,
  resolution: 'SEVEN_BIT',
}
const encoderWithPush: ControlDescriptor = {
  type: 'encoder',
  controlId: 'enc-1',
  label: 'Encoder 1',
  cc: 10,
  channel: 1,
  ringCc: 10,
  ringStyle: 'SINGLE_DOT',
  pushNote: 0,
  pushLed: 'ON_OFF',
}
const encoderNoPush: ControlDescriptor = { ...encoderWithPush, controlId: 'enc-2', pushNote: null }
const button: ControlDescriptor = {
  type: 'button',
  controlId: 'btn-25',
  label: 'Button 25',
  note: 25,
  channel: 1,
  ledFeedback: 'ON_OFF',
}
const bankButton: ControlDescriptor = {
  type: 'bankButton',
  controlId: 'bank-layer-a',
  label: 'A',
  note: 90,
  programChange: null,
  channel: 1,
  bankId: 'layer-a',
}

const profile: Pick<ControlSurfaceType, 'typeKey' | 'strips'> = {
  typeKey: 'xtc',
  strips: [
    { id: 'strip-1', fader: 'fader-1', select: 'btn-25', encoder: 'enc-1', flash: null },
  ],
}

function binding(
  id: number,
  controlId: string,
  bank: string | null,
  target: BindingTarget,
): ControlSurfaceBinding {
  return {
    id,
    projectId: 1,
    deviceTypeKey: 'xtc',
    controlId,
    bank,
    target,
    targetType: target.type,
    takeoverPolicy: null,
    sortOrder: id,
    health: { type: 'ok' },
  }
}

function controlDrop(descriptor: ControlDescriptor): SurfaceControlDrop {
  return {
    type: 'surface-control',
    controlId: descriptor.controlId,
    kinds: controlKinds(descriptor),
  }
}

const stripDrop: SurfaceDropData = { type: 'surface-strip', stripId: 'strip-1' }

const row: SurfaceRowDrag = {
  type: 'surface-row',
  target: { type: 'group', key: 'Movers' },
  name: 'Movers',
  detail: '4 fixtures',
}
const chip = (target: BindingTarget): SurfaceChipDrag => ({
  type: 'surface-chip',
  target,
  label: 'x',
  swatch: null,
})
const dimmer: BindingTarget = { type: 'groupProperty', groupName: 'Movers', propertyName: 'dimmer' }
const fire: BindingTarget = { type: 'fireCue', cueId: 4 }

describe('controlKinds', () => {
  it('gives an encoder both halves when it has a push note, and only the turn when it does not', () => {
    // `matchEvent` routes an encoder's CC to `Continuous` and its push note to `ButtonPress` on the
    // same control id, so one encoder legitimately takes a cue chip *and* a property one.
    expect(controlKinds(encoderWithPush)).toEqual(['continuous', 'button'])
    expect(controlKinds(encoderNoPush)).toEqual(['continuous'])
  })

  it('gives a bank button nothing at all', () => {
    // `route` answers `ResolvedInput.BankButton` and switches the bank before resolving a binding,
    // so a row here could never fire. Offering it would be offering a control that does nothing.
    expect(controlKinds(bankButton)).toEqual([])
    expect(canLand(chip(fire), controlDrop(bankButton))).toBe(false)
  })
})

describe('targetControlKind', () => {
  it('refuses the two variants no chip may carry', () => {
    // `strip` addresses a strip id (`refuseWrongSlot`) and `unknown` is never accepted from a
    // request (`refuseUnknown`) — it exists to be rebound, not re-sent.
    expect(targetControlKind({ type: 'strip', target: { type: 'group', key: 'Movers' } })).toBeNull()
    expect(targetControlKind({ type: 'unknown', targetType: 'future', rawPayload: '{}' })).toBeNull()
  })

  it('puts every record and busk-page target on a button', () => {
    // Each is a press, and a fader has no press. Pinned because the six were added to one run of
    // `case` labels, where a slip lands a target on the wrong half with no compiler complaint —
    // and the backend's `refuseWrongKind` would then refuse a drop the palette had just allowed.
    expect(targetControlKind({ type: 'applyLook', lookUuid: 'l' })).toBe('button')
    expect(targetControlKind({ type: 'pressTemplate', templateUuid: 't' })).toBe('button')
    expect(targetControlKind({ type: 'pressPad', padUuid: 'p' })).toBe('button')
    expect(targetControlKind({ type: 'buskPageNext' })).toBe('button')
    expect(targetControlKind({ type: 'buskPagePrev' })).toBe('button')
    expect(targetControlKind({ type: 'buskPageSet', pageUuid: 'g' })).toBe('button')
  })

  it('puts the busk-further plan’s five on a button — the mirror of BindingControlKind.kt (D14)', () => {
    // Two window commands and three sub-selection rewrites, each a press; `refuseWrongKind` would
    // refuse any of them on a fader, and this is the half that dims the drop before the 400.
    expect(targetControlKind({ type: 'buskFocusSet', windowName: 'Screen 2', focus: 'pads' })).toBe('button')
    expect(targetControlKind({ type: 'buskSheetToggle', windowName: 'Screen 2' })).toBe('button')
    expect(targetControlKind({ type: 'selectionNext' })).toBe('button')
    expect(targetControlKind({ type: 'selectionPrev' })).toBe('button')
    expect(targetControlKind({ type: 'selectionCells', mode: 'ODD' })).toBe('button')
    const fader = { type: 'surface-control', controlId: 'fader-1', kinds: ['continuous'] } as const
    expect(canLand({ type: 'surface-chip', target: { type: 'selectionCells', mode: 'ODD' }, label: 'Odd', swatch: null }, fader)).toBe(false)
  })
})

describe('canLand', () => {
  it('lands a row on a strip and nowhere else', () => {
    expect(canLand(row, stripDrop)).toBe(true)
    expect(canLand(row, controlDrop(fader))).toBe(false)
  })

  it('lands a chip on one control and never on a strip', () => {
    expect(canLand(chip(dimmer), controlDrop(fader))).toBe(true)
    expect(canLand(chip(dimmer), stripDrop)).toBe(false)
  })

  it('keeps a button target off a fader, which nothing on the backend does', () => {
    // A `fireCue` on a fader saves happily and then never fires — `dispatchContinuous` has no arm
    // for it. This predicate and the dim it drives are the whole of the warning.
    expect(canLand(chip(fire), controlDrop(fader))).toBe(false)
    expect(canLand(chip(fire), controlDrop(button))).toBe(true)
    expect(canLand(chip(fire), controlDrop(encoderWithPush))).toBe(true)
  })
})

describe('bindingWriteFor', () => {
  it('creates at the active bank', () => {
    const index = buildBindingIndex([], profile)
    expect(bindingWriteFor(chip(dimmer), controlDrop(fader), index, 'layer-b')).toEqual({
      kind: 'create',
      controlId: 'fader-1',
      bank: 'layer-b',
      target: dimmer,
    })
  })

  it('patches the control’s own row at the exact bank', () => {
    const index = buildBindingIndex(
      [binding(1, 'fader-1', 'layer-a', { type: 'blackout' })],
      profile,
    )
    expect(bindingWriteFor(chip(dimmer), controlDrop(fader), index, 'layer-a')).toMatchObject({
      kind: 'update',
      bindingId: 1,
    })
  })

  it('creates rather than retargeting a bank-agnostic row', () => {
    // The panel is drawing one bank. Patching the global row would silently change what this
    // control does on every *other* bank too — a change the operator was not shown.
    const index = buildBindingIndex([binding(1, 'fader-1', null, { type: 'blackout' })], profile)
    expect(bindingWriteFor(chip(dimmer), controlDrop(fader), index, 'layer-a')).toEqual({
      kind: 'create',
      controlId: 'fader-1',
      bank: 'layer-a',
      target: dimmer,
    })
  })

  it('creates a direct row on a control a strip is already driving', () => {
    // This is the *only* way "direct beats strip" is reachable from the UI: patching the strip row
    // would retarget all four of its controls instead of the one under the pointer.
    const index = buildBindingIndex(
      [binding(9, 'strip-1', null, { type: 'strip', target: { type: 'group', key: 'Movers' } })],
      profile,
    )
    expect(bindingWriteFor(chip(dimmer), controlDrop(fader), index, null)).toEqual({
      kind: 'create',
      controlId: 'fader-1',
      bank: null,
      target: dimmer,
    })
  })

  it('addresses a row drop at the strip id, wrapped in a strip target', () => {
    const index = buildBindingIndex([], profile)
    expect(bindingWriteFor(row, stripDrop, index, null)).toEqual({
      kind: 'create',
      controlId: 'strip-1',
      bank: null,
      target: { type: 'strip', target: { type: 'group', key: 'Movers' } },
    })
  })

  it('patches the strip’s own row when it already has one', () => {
    const index = buildBindingIndex(
      [binding(9, 'strip-1', null, { type: 'strip', target: { type: 'group', key: 'Hexes' } })],
      profile,
    )
    expect(bindingWriteFor(row, stripDrop, index, null)).toMatchObject({
      kind: 'update',
      bindingId: 9,
      target: { type: 'strip', target: { type: 'group', key: 'Movers' } },
    })
  })

  it('writes nothing for a drop that could not land', () => {
    const index = buildBindingIndex([], profile)
    expect(bindingWriteFor(row, controlDrop(fader), index, null)).toBeNull()
    expect(bindingWriteFor(chip(fire), controlDrop(fader), index, null)).toBeNull()
  })
})

describe('colour axes', () => {
  it('leaves an axis target continuous, so a fine-hue chip lands on a fader and not a button', () => {
    // `targetControlKind` switches on the kind alone; an axis changes what the fader *means*, not
    // which half of the dispatch reaches it — the mirror of `BindingControlKind.kt`.
    const fine: BindingTarget = {
      type: 'fixtureProperty',
      fixtureKey: 'hex-1',
      propertyName: 'rgbColour',
      colourAxis: 'hueFine',
    }
    expect(targetControlKind(fine)).toBe('continuous')
    expect(targetControlKind({ type: 'selectionProperty', propertyName: 'rgbColour', colourAxis: 'saturation' })).toBe('continuous')
    expect(targetControlKind({ type: 'encoderBankSet', propertyName: 'rgbColour', colourAxis: 'brightness' })).toBe('button')
    const chip: SurfaceChipDrag = { type: 'surface-chip', target: fine, label: 'hue fine', swatch: null }
    const onFader: SurfaceControlDrop = { type: 'surface-control', controlId: 'fader-1', kinds: controlKinds(fader) }
    const onButton: SurfaceControlDrop = { type: 'surface-control', controlId: 'btn-25', kinds: controlKinds(button) }
    expect(canLand(chip, onFader)).toBe(true)
    expect(canLand(chip, onButton)).toBe(false)
  })
})
