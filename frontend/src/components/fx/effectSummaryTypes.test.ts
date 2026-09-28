import { describe, expect, it } from 'vitest'
import {
  fromCueAdHocEffect,
  fromFixtureDirectEffect,
  fromFixtureIndirectEffect,
  fromGroupActiveEffect,
  fromLookEffect,
} from './effectSummaryTypes'
import type { LookEffect } from '@/api/looksApi'
import type { CueAdHocEffect } from '@/api/cuesApi'
import type { FixtureDirectEffect, FixtureIndirectEffect } from '@/store/fixtureFx'
import type { GroupActiveEffect } from '@/api/groupsApi'

const MASTER = 'aaaaaaaa-0000-0000-0000-000000000002'

/**
 * Every adapter must carry `speedMasterUuid` into the normalised summary — an adapter that
 * drops it renders its surface's effects as master-1 even though they run elsewhere, which
 * is precisely the quiet-display-lie the M-chip exists to prevent.
 */
describe('effectSummaryTypes speed-master threading', () => {
  it('fromLookEffect carries it', () => {
    const e: LookEffect = {
      targetType: 'deferred',
      targetKey: '',
      effectType: 'Pulse', category: 'dimmer', propertyName: 'dimmer',
      beatDivision: 1, blendMode: 'OVERRIDE', distribution: 'LINEAR',
      phaseOffset: 0, elementMode: null, elementFilter: null, stepTiming: null,
      parameters: {}, speedMasterUuid: MASTER,
    }
    expect(fromLookEffect(e).speedMasterUuid).toBe(MASTER)
  })

  it('fromCueAdHocEffect carries it', () => {
    const e: CueAdHocEffect = {
      targetType: 'fixture', targetKey: 'hex-1', effectType: 'Pulse', category: 'dimmer',
      propertyName: 'dimmer', beatDivision: 1, blendMode: 'OVERRIDE', distribution: 'LINEAR',
      phaseOffset: 0, elementMode: null, elementFilter: null, stepTiming: null,
      parameters: {}, speedMasterUuid: MASTER,
    }
    expect(fromCueAdHocEffect(e).speedMasterUuid).toBe(MASTER)
  })

  it('fromFixtureDirectEffect carries it', () => {
    const e: FixtureDirectEffect = {
      id: 1, effectType: 'Pulse', targetKey: 'hex-1', propertyName: 'dimmer',
      beatDivision: 1, blendMode: 'OVERRIDE', isRunning: true, phaseOffset: 0,
      currentPhase: 0, parameters: {}, isGroupTarget: false, distributionStrategy: null,
      elementFilter: null, stepTiming: false, cueId: null,
      speedMasterUuid: MASTER,
    }
    expect(fromFixtureDirectEffect(e).speedMasterUuid).toBe(MASTER)
  })

  it('fromFixtureIndirectEffect carries it', () => {
    const e: FixtureIndirectEffect = {
      id: 1, effectType: 'Pulse', groupName: 'wash', propertyName: 'dimmer',
      beatDivision: 1, blendMode: 'OVERRIDE', isRunning: true, phaseOffset: 0,
      currentPhase: 0, parameters: {}, distributionStrategy: 'LINEAR', stepTiming: false,
      speedMasterUuid: MASTER,
    }
    expect(fromFixtureIndirectEffect(e).speedMasterUuid).toBe(MASTER)
  })

  it('fromGroupActiveEffect carries it', () => {
    const e: GroupActiveEffect = {
      id: 1, effectType: 'Pulse', propertyName: 'dimmer', beatDivision: 1,
      blendMode: 'OVERRIDE', distribution: 'LINEAR', isRunning: true, phaseOffset: 0,
      currentPhase: 0, parameters: {}, elementMode: null, elementFilter: null,
      stepTiming: false, cueId: null, speedMasterUuid: MASTER,
    }
    expect(fromGroupActiveEffect(e).speedMasterUuid).toBe(MASTER)
  })

  it('an absent reference normalises to null, the master-1 default', () => {
    const e: LookEffect = {
      targetType: 'deferred',
      targetKey: '',
      effectType: 'Pulse', category: 'dimmer', propertyName: 'dimmer',
      beatDivision: 1, blendMode: 'OVERRIDE', distribution: 'LINEAR',
      phaseOffset: 0, elementMode: null, elementFilter: null, stepTiming: null,
      parameters: {},
    }
    expect(fromLookEffect(e).speedMasterUuid).toBeNull()
  })
})

/**
 * The rate master is a second, independent reference with exactly the same failure mode: an
 * adapter that drops it shows a wall-clock effect as running unscaled while it is in fact
 * being scaled by a master.
 */
describe('effectSummaryTypes rate-master threading', () => {
  const RATE = 'aaaaaaaa-0000-0000-0000-000000000003'

  it('fromLookEffect carries it independently of the speed master', () => {
    const e: LookEffect = {
      targetType: 'deferred',
      targetKey: '',
      effectType: 'CandleFlicker', category: 'dimmer', propertyName: 'dimmer',
      beatDivision: 4, blendMode: 'OVERRIDE', distribution: 'LINEAR',
      phaseOffset: 0, elementMode: null, elementFilter: null, stepTiming: null,
      parameters: {}, speedMasterUuid: MASTER, rateSpeedMasterUuid: RATE,
    }
    const summary = fromLookEffect(e)
    expect(summary.speedMasterUuid).toBe(MASTER)
    expect(summary.rateSpeedMasterUuid).toBe(RATE)
  })

  it('fromCueAdHocEffect carries it', () => {
    const e: CueAdHocEffect = {
      targetType: 'fixture', targetKey: 'hex-1', effectType: 'CandleFlicker',
      category: 'dimmer', propertyName: 'dimmer', beatDivision: 4,
      blendMode: 'OVERRIDE', distribution: 'LINEAR', phaseOffset: 0,
      elementMode: null, elementFilter: null, stepTiming: null,
      parameters: {}, rateSpeedMasterUuid: RATE,
    }
    expect(fromCueAdHocEffect(e).rateSpeedMasterUuid).toBe(RATE)
  })

  it('an absent rate reference normalises to null, meaning unscaled', () => {
    const e: LookEffect = {
      targetType: 'deferred',
      targetKey: '',
      effectType: 'Pulse', category: 'dimmer', propertyName: 'dimmer',
      beatDivision: 1, blendMode: 'OVERRIDE', distribution: 'LINEAR',
      phaseOffset: 0, elementMode: null, elementFilter: null, stepTiming: null,
      parameters: {},
    }
    expect(fromLookEffect(e).rateSpeedMasterUuid).toBeNull()
  })
})
