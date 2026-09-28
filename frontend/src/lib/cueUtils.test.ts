import { describe, expect, it } from 'vitest'
import { buildCueInput, formatFadeDuration, parseFadeDuration } from './cueUtils'
import type { Cue } from '@/api/cuesApi'

describe('parseFadeDuration', () => {
  it('reads an explicit unit', () => {
    expect(parseFadeDuration('500ms')).toBe(500)
    expect(parseFadeDuration('2s')).toBe(2000)
    expect(parseFadeDuration('1.5s')).toBe(1500)
    expect(parseFadeDuration('1.5m')).toBe(90_000)
  })

  it('reads a bare number as seconds', () => {
    expect(parseFadeDuration('3')).toBe(3000)
    expect(parseFadeDuration('0.25')).toBe(250)
  })

  it('tolerates whitespace, case and long unit spellings', () => {
    expect(parseFadeDuration('  2 S ')).toBe(2000)
    expect(parseFadeDuration('750 MS')).toBe(750)
    expect(parseFadeDuration('4 sec')).toBe(4000)
    expect(parseFadeDuration('2 min')).toBe(120_000)
  })

  it('treats empty, zero and "snap" as no fade', () => {
    expect(parseFadeDuration('')).toBeNull()
    expect(parseFadeDuration('   ')).toBeNull()
    expect(parseFadeDuration('0')).toBeNull()
    expect(parseFadeDuration('0ms')).toBeNull()
    expect(parseFadeDuration('snap')).toBeNull()
    expect(parseFadeDuration('SNAP')).toBeNull()
  })

  it('rejects text it cannot read', () => {
    expect(parseFadeDuration('fast')).toBeUndefined()
    expect(parseFadeDuration('2 beats')).toBeUndefined()
    expect(parseFadeDuration('-2s')).toBeUndefined()
    expect(parseFadeDuration('2s 500ms')).toBeUndefined()
    expect(parseFadeDuration('.')).toBeUndefined()
  })

  it('round-trips what the table displays', () => {
    for (const ms of [250, 500, 2000, 2500, 90_000]) {
      expect(parseFadeDuration(formatFadeDuration(ms))).toBe(ms)
    }
    expect(formatFadeDuration(null)).toBe('')
    expect(formatFadeDuration(0)).toBe('')
  })
})

describe('buildCueInput', () => {
  /**
   * `buildCueInput` rebuilds `layers` and `triggers` field-by-field (to strip response-only fields
   * like `lookName`), which means a field missing from that rebuild is silently dropped on *every
   * inline cue edit* — the change appears to save and the data quietly vanishes. Every field is
   * pinned individually rather than by a deep-equal, because a deep-equal against a fixture built in
   * this file would pass just as happily if both sides were missing the same field.
   */
  it('carries every layer field through the rebuild', () => {
    const input = buildCueInput(cueWithOneLayer())
    const layer = input.layers[0]

    expect(layer.lookId).toBe(7)
    // Session 3: a layer applies a Look **or** a template, so `templateId` is a field too. Missing
    // from the rebuild it would be dropped on every inline cue edit — and because both ids are
    // optional, the compiler would not have said a word.
    expect('templateId' in layer).toBe(true)
    expect(layer.sortOrder).toBe(3)
    expect(layer.enabled).toBe(false)
    expect(layer.targets).toEqual([{ type: 'group', key: 'front-wash' }])
    expect(layer.propertyMask).toBe('COLOUR,POSITION')
    expect(layer.blendMode).toBe('MULTIPLY')
    expect(layer.amount).toBe(0.5)
    expect(layer.stomp).toBe(true)
    expect(layer.speedMasterUuid).toBe('aaaaaaaa-0000-0000-0000-000000000002')
    expect(layer.rateSpeedMasterUuid).toBe('aaaaaaaa-0000-0000-0000-000000000003')
    expect(layer.delayMs).toBe(1500)
    expect(layer.intervalMs).toBe(4000)
    expect(layer.randomWindowMs).toBe(250)
  })

  it('carries every trigger field through the rebuild', () => {
    // The same silent-drop failure as the layer rebuild, on the other list `buildCueInput`
    // reconstructs. The fixture's trigger sets all six to non-defaults so an omission cannot hide
    // behind a value that happens to match the default.
    const input = buildCueInput(cueWithOneLayer())
    const trigger = input.triggers![0]

    expect(trigger.triggerType).toBe('DEACTIVATION')
    expect(trigger.delayMs).toBe(750)
    expect(trigger.intervalMs).toBe(2000)
    expect(trigger.randomWindowMs).toBe(125)
    expect(trigger.scriptId).toBe(42)
    expect(trigger.sortOrder).toBe(2)
  })

  it('strips the response-only scriptName from a trigger', () => {
    // `scriptName` is the server's resolved label for `scriptId`, present on `CueTriggerDetail`
    // and absent from `CueTrigger`. Echoing it back would invite the server to trust a client's
    // idea of what a script is called — the same rule as the layer's `source`.
    const input = buildCueInput(cueWithOneLayer())
    expect('scriptName' in input.triggers![0]).toBe(false)
  })

  it('strips the response-only source, which is why the rebuild exists at all', () => {
    // `source` is what the server resolved the layer's referent to — kind, id, uuid and name. It is
    // read-only: `lookId` / `templateId` are the write fields, and echoing the resolved object back
    // would invite the server to trust a client's idea of a record's identity.
    const input = buildCueInput(cueWithOneLayer())
    expect('source' in input.layers[0]).toBe(false)
  })

  it('carries a template layer’s id, not just a Look’s', () => {
    // The other half of the polymorphic referent. A cue that layers a template must survive an
    // inline edit of any other field on the cue.
    const cue = cueWithOneLayer()
    const templated = {
      ...cue,
      layers: [{ ...cue.layers[0], lookId: undefined, templateId: 11 }],
    }
    const layer = buildCueInput(templated).layers[0]
    expect(layer.templateId).toBe(11)
    expect(layer.lookId).toBeUndefined()
  })

  /**
   * `presetApplications` is gone from the wire — the cue rewrite retired it in favour of `layers` —
   * so `buildCueInput` must never echo one back even if a stale caller still hands it one.
   */
  it('does not send preset applications', () => {
    expect('presetApplications' in buildCueInput(cueWithOneLayer())).toBe(false)
  })

  it('carries the cue-level stomp, which is not the per-layer one', () => {
    // Two flags with the same name: the layer's suppresses effects inside the cue's own
    // composition, the cue's removes the effects running *under* the cue when it fires. The client
    // modelled only the layer one for a session, so a duplicate POSTed a copy that silently took
    // the server default — and the PUT route overwrites the field rather than preserving it.
    const input = buildCueInput(cueWithOneLayer())
    expect(input.stomp).toBe(true)
    expect(input.layers[0].stomp).toBe(true)

    // The server omits the flag when false (`encodeDefaults = false`), so absent must stay absent
    // rather than being rebuilt as `false`.
    const off = buildCueInput({ ...cueWithOneLayer(), stomp: undefined })
    expect(off.stomp).toBeUndefined()
  })

})

// A `reorderCueLayers` block stood here — move-and-renumber, renumber-a-gappy-list, and an
// out-of-range index that must not drop a layer. It went with the function: see `cueUtils.ts` for
// why both layer-order helpers were deleted rather than kept annotated a third time.

/** One cue holding a single layer and a single trigger, every optional field at a non-default. */
function cueWithOneLayer(): Cue {
  return {
    id: 1,
    name: 'open',
    layers: [
      {
        lookId: 7,
        source: { kind: 'LOOK', id: 7, uuid: 'u7', name: 'warm-pulse' },
        sortOrder: 3,
        enabled: false,
        targets: [{ type: 'group', key: 'front-wash' }],
        propertyMask: 'COLOUR,POSITION',
        blendMode: 'MULTIPLY',
        amount: 0.5,
        stomp: true,
        speedMasterUuid: 'aaaaaaaa-0000-0000-0000-000000000002',
        rateSpeedMasterUuid: 'aaaaaaaa-0000-0000-0000-000000000003',
        delayMs: 1500,
        intervalMs: 4000,
        randomWindowMs: 250,
      },
    ],
    adHocEffects: [],
    propertyAssignments: [],
    triggers: [
      {
        triggerType: 'DEACTIVATION',
        delayMs: 750,
        intervalMs: 2000,
        randomWindowMs: 125,
        scriptId: 42,
        scriptName: 'house-lights-up',
        sortOrder: 2,
      },
    ],
    cueStackId: 1,
    cueStackName: 'show',
    sortOrder: 0,
    autoAdvance: false,
    autoAdvanceDelayMs: null,
    fadeDurationMs: null,
    fadeCurve: 'LINEAR',
    cueNumber: null,
    cueNumberAuto: true,
    notes: null,
    stomp: true,
    cueType: 'STANDARD',
    canEdit: true,
    canDelete: true,
  }
}
