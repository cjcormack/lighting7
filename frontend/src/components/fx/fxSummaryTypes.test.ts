import { describe, expect, it } from 'vitest'
import {
  fromCueAdHocFx,
  fromFixtureDirectFx,
  fromFixtureIndirectFx,
  fromGroupActiveFx,
  fromLookFx,
} from './fxSummaryTypes'
import type { LookFx } from '@/api/looksApi'
import type { CueAdHocFx } from '@/api/cuesApi'
import type { FixtureDirectFx, FixtureIndirectFx } from '@/store/fixtureFx'
import type { GroupActiveFx } from '@/api/groupsApi'

const MASTER = 'aaaaaaaa-0000-0000-0000-000000000002'

/**
 * Every adapter must carry `speedMasterUuid` into the normalised summary — an adapter that
 * drops it renders its surface's effects as master-1 even though they run elsewhere, which
 * is precisely the quiet-display-lie the M-chip exists to prevent.
 */
describe('fxSummaryTypes speed-master threading', () => {
  it('fromLookFx carries it', () => {
    const e: LookFx = {
      targetType: 'deferred',
      targetKey: '',
      fxType: 'Pulse', family: 'intensity', propertyName: 'dimmer',
      cycle: 1, blendMode: 'OVERRIDE', order: 'NORMAL',
      phaseOffset: 0, elementMode: null, elementFilter: null, timing: null,
      parameters: {}, speedMasterUuid: MASTER,
    }
    expect(fromLookFx(e).speedMasterUuid).toBe(MASTER)
  })

  it('fromCueAdHocFx carries it', () => {
    const e: CueAdHocFx = {
      targetType: 'fixture', targetKey: 'hex-1', fxType: 'Pulse', family: 'intensity',
      propertyName: 'dimmer', cycle: 1, blendMode: 'OVERRIDE', order: 'NORMAL',
      phaseOffset: 0, elementMode: null, elementFilter: null, timing: null,
      parameters: {}, speedMasterUuid: MASTER,
    }
    expect(fromCueAdHocFx(e).speedMasterUuid).toBe(MASTER)
  })

  it('fromFixtureDirectFx carries it', () => {
    const e: FixtureDirectFx = {
      id: 1, fxType: 'Pulse', targetKey: 'hex-1', propertyName: 'dimmer',
      cycle: 1, blendMode: 'OVERRIDE', isRunning: true, phaseOffset: 0,
      currentPhase: 0, parameters: {}, isGroupTarget: false, order: null,
      elementFilter: null, timing: 'CYCLE', cueId: null,
      speedMasterUuid: MASTER,
    }
    expect(fromFixtureDirectFx(e).speedMasterUuid).toBe(MASTER)
  })

  it('fromFixtureIndirectFx carries it', () => {
    const e: FixtureIndirectFx = {
      id: 1, fxType: 'Pulse', groupName: 'wash', propertyName: 'dimmer',
      cycle: 1, blendMode: 'OVERRIDE', isRunning: true, phaseOffset: 0,
      currentPhase: 0, parameters: {}, order: 'NORMAL', timing: 'CYCLE',
      speedMasterUuid: MASTER,
    }
    expect(fromFixtureIndirectFx(e).speedMasterUuid).toBe(MASTER)
  })

  it('fromGroupActiveFx carries it', () => {
    const e: GroupActiveFx = {
      id: 1, fxType: 'Pulse', propertyName: 'dimmer', cycle: 1,
      blendMode: 'OVERRIDE', order: 'NORMAL', isRunning: true, phaseOffset: 0,
      currentPhase: 0, parameters: {}, elementMode: null, elementFilter: null,
      timing: 'CYCLE', cueId: null, speedMasterUuid: MASTER,
    }
    expect(fromGroupActiveFx(e).speedMasterUuid).toBe(MASTER)
  })

  it('an absent reference normalises to null, the master-1 default', () => {
    const e: LookFx = {
      targetType: 'deferred',
      targetKey: '',
      fxType: 'Pulse', family: 'intensity', propertyName: 'dimmer',
      cycle: 1, blendMode: 'OVERRIDE', order: 'NORMAL',
      phaseOffset: 0, elementMode: null, elementFilter: null, timing: null,
      parameters: {},
    }
    expect(fromLookFx(e).speedMasterUuid).toBeNull()
  })
})

/**
 * The rate master is a second, independent reference with exactly the same failure mode: an
 * adapter that drops it shows a wall-clock effect as running unscaled while it is in fact
 * being scaled by a master.
 */
describe('fxSummaryTypes rate-master threading', () => {
  const RATE = 'aaaaaaaa-0000-0000-0000-000000000003'

  it('fromLookFx carries it independently of the speed master', () => {
    const e: LookFx = {
      targetType: 'deferred',
      targetKey: '',
      fxType: 'CandleFlicker', family: 'intensity', propertyName: 'dimmer',
      cycle: 4, blendMode: 'OVERRIDE', order: 'NORMAL',
      phaseOffset: 0, elementMode: null, elementFilter: null, timing: null,
      parameters: {}, speedMasterUuid: MASTER, rateSpeedMasterUuid: RATE,
    }
    const summary = fromLookFx(e)
    expect(summary.speedMasterUuid).toBe(MASTER)
    expect(summary.rateSpeedMasterUuid).toBe(RATE)
  })

  it('fromCueAdHocFx carries it', () => {
    const e: CueAdHocFx = {
      targetType: 'fixture', targetKey: 'hex-1', fxType: 'CandleFlicker',
      family: 'intensity', propertyName: 'dimmer', cycle: 4,
      blendMode: 'OVERRIDE', order: 'NORMAL', phaseOffset: 0,
      elementMode: null, elementFilter: null, timing: null,
      parameters: {}, rateSpeedMasterUuid: RATE,
    }
    expect(fromCueAdHocFx(e).rateSpeedMasterUuid).toBe(RATE)
  })

  it('an absent rate reference normalises to null, meaning unscaled', () => {
    const e: LookFx = {
      targetType: 'deferred',
      targetKey: '',
      fxType: 'Pulse', family: 'intensity', propertyName: 'dimmer',
      cycle: 1, blendMode: 'OVERRIDE', order: 'NORMAL',
      phaseOffset: 0, elementMode: null, elementFilter: null, timing: null,
      parameters: {},
    }
    expect(fromLookFx(e).rateSpeedMasterUuid).toBeNull()
  })
})
