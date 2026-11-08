import { describe, expect, it } from 'vitest'
import { oscCheatSheetHtml } from './oscCheatSheet'
import type { OscAddressList } from './oscAddresses'

const list: OscAddressList = {
  prefix: '/lighting7',
  patterns: [],
  addresses: [
    { address: '/lighting7/tempo/1', pattern: '/lighting7/tempo/{master}', args: 'f BPM', does: '', reaches: 'Tempo of M1 Main', target: { type: 'speedMasterSetBpm', masterUuid: 'm', bpm: 120 } },
    { address: '/lighting7/stack/act-1/cue/5/go', pattern: '/lighting7/stack/{stack}/cue/{n}/go', args: '–', does: '', reaches: 'GO TO 5 Storm & <Rain> in Act 1', target: { type: 'cueStackGoTo', stackUuid: 's', entryUuid: 'e' } },
  ],
  ambiguous: [{ message: '`storm` names 2 cues: Storm!, storm', records: [], addresses: ['/lighting7/cue/storm/on'] }],
  unaddressed: [{ kind: 'STACK', id: 1, uuid: 'u', name: '???', reason: 'Its name has no letter or digit, so it has no address' }],
}

describe('oscCheatSheetHtml', () => {
  const html = oscCheatSheetHtml(list, 'The Tempest', new Date('2026-11-07T20:00:00Z'))

  it('prints each address under its kind, stacks before masters, with what it reaches escaped', () => {
    expect(html.indexOf('<h2>Cue stacks</h2>')).toBeLessThan(html.indexOf('<h2>Masters and tempo</h2>'))
    expect(html).toContain('<td class="addr">/lighting7/stack/act-1/cue/5/go</td><td></td><td>GO TO 5 Storm &amp; &lt;Rain&gt; in Act 1</td>')
    expect(html).toContain('<td>f BPM</td>')
    expect(html).not.toContain('<h2>Looks</h2>')
  })

  it('says which names have no address, and why', () => {
    expect(html).toContain('<h2>No address</h2>')
    expect(html).toContain('???: Its name has no letter or digit, so it has no address')
    expect(html).toContain('/lighting7/cue/storm/on')
    expect(html).toContain('<title>OSC addresses · The Tempest</title>')
  })
})
