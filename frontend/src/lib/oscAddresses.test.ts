import { describe, expect, it } from 'vitest'
import {
  describeAddressChange,
  newestWarningId,
  noOscAddressReason,
  oscAddressesFor,
  oscAddressVerb,
  unseenWarnings,
  type OscAddress,
  type OscAddressList,
} from './oscAddresses'

const address = (address: string, record: OscAddress['record']): OscAddress => ({
  address,
  pattern: '/lighting7/x',
  args: '–',
  does: '',
  reaches: '',
  target: { type: 'blackout' },
  record,
})

const list: OscAddressList = {
  prefix: '/lighting7',
  patterns: [],
  addresses: [
    address('/lighting7/cue/thunder/on', { kind: 'CUE', id: 4, uuid: 'c4' }),
    address('/lighting7/stack/act-1/cue/5/go', { kind: 'ENTRY', id: 9, uuid: 'e9' }),
    address('/lighting7/stack/act-1/cue/5/standby', { kind: 'ENTRY', id: 9, uuid: 'e9' }),
    address('/lighting7/stack/act-1/go', { kind: 'STACK', id: 9, uuid: 's9' }),
    address('/lighting7/blackout', null),
  ],
  ambiguous: [{ message: '`storm` names 2 cues: Storm!, storm', records: [{ kind: 'CUE', id: 1, uuid: 'c1' }, { kind: 'CUE', id: 2, uuid: 'c2' }], addresses: [] }],
  unaddressed: [{ kind: 'LOOK', id: 3, uuid: 'l3', name: '★', reason: 'Its name has no letter or digit, so it has no address' }],
}

describe('oscAddressesFor', () => {
  it('lists a record’s addresses in the order its keys are given, telling kinds that share an id apart', () => {
    expect(oscAddressesFor(list, [{ kind: 'ENTRY', id: 9 }, { kind: 'CUE', id: 4 }]).map((a) => a.address)).toEqual([
      '/lighting7/stack/act-1/cue/5/go',
      '/lighting7/stack/act-1/cue/5/standby',
      '/lighting7/cue/thunder/on',
    ])
    expect(oscAddressesFor(list, [{ kind: 'STACK', id: 9 }]).map(oscAddressVerb)).toEqual(['go'])
  })
})

describe('noOscAddressReason', () => {
  it('says the desk’s reason for a record with none, and nothing for one that has an address', () => {
    expect(noOscAddressReason(list, [{ kind: 'LOOK', id: 3 }])).toBe('Its name has no letter or digit, so it has no address')
    expect(noOscAddressReason(list, [{ kind: 'CUE', id: 2 }])).toBe('`storm` names 2 cues: Storm!, storm')
    expect(noOscAddressReason(list, [{ kind: 'CUE', id: 4 }])).toBeNull()
    expect(noOscAddressReason(list, [{ kind: 'PAD', id: 99 }])).toBeNull()
  })
})

describe('the moved-address warning', () => {
  it('says what an address reaches now, or that it no longer works and why', () => {
    expect(describeAddressChange({ address: '/a', was: 'GO Act 1', now: 'GO Act 2' })).toBe('/a now reaches GO Act 2 (was GO Act 1)')
    expect(describeAddressChange({ address: '/a', was: 'GO Act 1', now: null, reason: 'No stack' })).toBe('/a no longer works (was GO Act 1) — No stack')
    expect(describeAddressChange({ address: '/a', was: null, now: 'GO Act 1' })).toBe('/a now reaches GO Act 1')
  })

  it('announces only the warnings newer than those seen, oldest first', () => {
    const warnings = [3, 2, 1].map((id) => ({ id, at: '', changes: [] }))
    expect(unseenWarnings(warnings, 1).map((w) => w.id)).toEqual([2, 3])
    expect(unseenWarnings(undefined, 0)).toEqual([])
    expect(newestWarningId(warnings)).toBe(3)
    expect(newestWarningId(undefined)).toBe(0)
  })
})
