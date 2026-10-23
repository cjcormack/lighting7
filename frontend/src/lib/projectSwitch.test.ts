import { describe, expect, it } from 'vitest'
import type { ProjectSummary } from '../api/projectApi'
import { NOT_HELD } from '../api/venueHoldApi'
import { NOTHING_SAFE, type CommandRun } from '../api/rigSafetyApi'
import {
  deskNameOf,
  documentTitleOf,
  makeCurrentRefusal,
  pinChipOf,
  rigDotOf,
  switchConfirmOf,
  switcherDetailOf,
  switcherGroupsOf,
  switcherOrderOf,
  venueMissing,
} from './projectSwitch'

/** Navigation plan §4: the switcher's words, its order and the one confirmation's lines. */

const show = (id: number, name: string, extra: Partial<ProjectSummary> = {}): ProjectSummary => ({
  id,
  name,
  description: null,
  isCurrent: false,
  kind: 'SHOW',
  ...extra,
})

const onVenue = (version: number, installed: number, behind: 'NONE' | 'DRAWING' | 'OUTPUT' = 'NONE') => ({
  venue: { uuid: 'v', id: 16, name: 'TCH venue', version, installed, behind },
})

const held = { projectId: 18, held: true, pinned: 7, installed: 8, runAgainst: false }

describe('the desk name and the title', () => {
  it('falls back to Lighting desk', () => {
    expect(deskNameOf(undefined)).toBe('Lighting desk')
    expect(deskNameOf({ friendlyName: '  ' })).toBe('Lighting desk')
    expect(deskNameOf({ friendlyName: 'Hall desk' })).toBe('Hall desk')
  })

  it('titles the window with the show on the rig and the desk', () => {
    expect(documentTitleOf({ name: 'Sleeping Beauty' }, 'Hall desk')).toBe('Sleeping Beauty · Hall desk')
    expect(documentTitleOf(null, 'Hall desk')).toBe('Hall desk')
  })
})

describe('the dot', () => {
  it('is sending, held, blacked out or offline — the blackout over the hold', () => {
    expect(rigDotOf(18, NOT_HELD, NOTHING_SAFE, true)).toBe('sending')
    expect(rigDotOf(18, held, NOTHING_SAFE, true)).toBe('held')
    expect(rigDotOf(18, held, { ...NOTHING_SAFE, projectId: 18, blackout: true }, true)).toBe('blackout')
    expect(rigDotOf(18, held, NOTHING_SAFE, false)).toBe('offline')
  })

  it("reads another project's hold or blackout as nothing", () => {
    expect(rigDotOf(18, { ...held, projectId: 26 }, { ...NOTHING_SAFE, projectId: 26, blackout: true }, true)).toBe('sending')
  })
})

describe('the pin chip', () => {
  it('names the pin, and held or a newer version', () => {
    expect(pinChipOf(show(18, 'Sleeping Beauty', onVenue(7, 8, 'OUTPUT')), held)).toEqual({
      label: 'TCH venue v7',
      status: 'held',
      tone: 'held',
    })
    expect(pinChipOf(show(18, 'Sleeping Beauty', onVenue(7, 8, 'DRAWING')), NOT_HELD)).toEqual({
      label: 'TCH venue v7',
      status: 'v8 published',
      tone: 'drawing',
    })
    expect(pinChipOf(show(18, 'Sleeping Beauty', onVenue(8, 8)), NOT_HELD)?.status).toBeNull()
    expect(pinChipOf(show(1, 'Default'), NOT_HELD)).toBeNull()
  })
})

describe('the menu', () => {
  const projects = [
    show(5, 'Tech Forum'),
    show(16, 'TCH venue', { kind: 'VENUE', venueSummary: { version: 8, shows: 2 } }),
    show(18, 'Sleeping Beauty', { isCurrent: true, ...onVenue(7, 8, 'OUTPUT') }),
    show(1, 'default'),
    show(26, 'TCH Test', onVenue(7, 8, 'OUTPUT')),
    show(30, 'Annex', { kind: 'VENUE', venueSummary: { version: null } }),
  ]

  it('orders On the rig, then Shows and Venues, each by name', () => {
    const groups = switcherGroupsOf(projects, '')
    expect(groups.onRig?.name).toBe('Sleeping Beauty')
    expect(groups.shows.map((p) => p.name)).toEqual(['default', 'TCH Test', 'Tech Forum'])
    expect(groups.venues.map((p) => p.name)).toEqual(['Annex', 'TCH venue'])
    expect(switcherOrderOf(groups).map((p) => p.id)).toEqual([18, 1, 26, 5, 30, 16])
  })

  it('filters by name, and the first match is the first drawn', () => {
    const groups = switcherGroupsOf(projects, 'tch')
    expect(groups.onRig).toBeNull()
    expect(switcherOrderOf(groups).map((p) => p.name)).toEqual(['TCH Test', 'TCH venue'])
  })

  it('details each row', () => {
    expect(switcherDetailOf(projects[2], held)).toBe('TCH venue v7 · held')
    expect(switcherDetailOf(projects[4], NOT_HELD)).toBe('TCH venue v7 · v8 published')
    expect(switcherDetailOf(projects[0], NOT_HELD)).toBe('no venue')
    expect(switcherDetailOf(projects[1], NOT_HELD)).toBe('v8 · 2 shows')
    expect(switcherDetailOf(projects[5], NOT_HELD)).toBe('no version yet')
  })

  it('refuses make current for a show whose venue is not on this desk', () => {
    const missing = show(40, 'Old show', { venue: { uuid: 'x', id: null, name: null, version: 3 } })
    expect(makeCurrentRefusal(missing)).toBe('Its venue, v3, isn’t on this desk')
    expect(makeCurrentRefusal(projects[4])).toBeNull()
  })

  it('refuses it too for a show pinned past its venue’s newest version, and its chip says so', () => {
    const ahead = show(41, 'Next show', { venue: { uuid: 'v', id: 16, name: 'TCH venue', version: 9, installed: 8 } })
    expect(venueMissing(ahead)).toBe(true)
    expect(makeCurrentRefusal(ahead)).toBe('Its venue, TCH venue v9, isn’t on this desk')
    expect(pinChipOf(ahead, NOT_HELD)).toEqual({ label: 'TCH venue v9', status: 'not on this desk', tone: 'missing' })
    // A venue here that holds no version yet has none to load.
    expect(venueMissing(show(43, 'Early', { venue: { uuid: 'v', id: 16, name: 'TCH venue', version: 1, installed: null } }))).toBe(true)
    // Installed and pinned alike, or the pin behind it, is here.
    expect(venueMissing(show(42, 'Same', { venue: { uuid: 'v', id: 16, name: 'TCH venue', version: 8, installed: 8 } }))).toBe(false)
    expect(venueMissing(projects[4])).toBe(false)
  })
})

describe('the confirmation', () => {
  const current = { id: 18, name: 'Sleeping Beauty' }

  it("names the incoming show's parks", () => {
    expect(switchConfirmOf(show(26, 'TCH Test', { parks: 4 }), current, NOTHING_SAFE)).toEqual({
      title: 'Put TCH Test on the rig?',
      body: 'Sleeping Beauty’s cues, programmer and FX stop, and every channel goes dark except those TCH Test parks (4).',
      lines: [],
      confirmLabel: 'Put TCH Test on the rig',
    })
    expect(switchConfirmOf(show(26, 'TCH Test', { parks: 0 }), current, NOTHING_SAFE).body).toBe(
      'Sleeping Beauty’s cues, programmer and FX stop, and every channel goes dark: TCH Test parks none.',
    )
    // A desk older than the count says the rule without it.
    expect(switchConfirmOf(show(26, 'TCH Test'), current, NOTHING_SAFE).body).toMatch(/except those TCH Test parks\.$/)
  })

  it('says the incoming show opens held when it is behind on output', () => {
    const { lines } = switchConfirmOf(show(26, 'TCH Test', { parks: 4, ...onVenue(7, 8, 'OUTPUT') }), current, NOTHING_SAFE)
    expect(lines).toEqual([
      {
        kind: 'held',
        text: 'TCH Test is behind TCH venue on output (v7, v8 published). It opens held: only parked channels reach the rig.',
      },
    ])
    expect(switchConfirmOf(show(26, 'TCH Test', onVenue(7, 8, 'DRAWING')), current, NOTHING_SAFE).lines).toEqual([])
  })

  it("says a bulk run on the outgoing show is dropped, and a blackout left on the incoming one", () => {
    const run: CommandRun = {
      id: 'r',
      role: 'LAMP_ON',
      purpose: 'BULK',
      startedAt: '',
      finished: false,
      units: [
        { fixture: 'a', fixtureName: 'MAC 1', command: 'lampOn', status: 'DONE' },
        { fixture: 'b', fixtureName: 'MAC 2', command: 'lampOn', status: 'WAITING' },
      ],
    }
    const safety = { ...NOTHING_SAFE, projectId: 18, run }
    const confirm = switchConfirmOf(show(26, 'TCH Test', { safeBlackout: true }), current, safety)
    expect(confirm.lines).toEqual([
      { kind: 'run', text: 'Sleeping Beauty’s lamp on run stops at 1 of 2: the units it hasn’t reached are left as they are.' },
      { kind: 'blackout', text: 'TCH Test was made safe and is still blacked out: it opens dark, with Undo on its banner.' },
    ])
    expect(switchConfirmOf(show(26, 'TCH Test'), current, { ...safety, run: { ...run, finished: true } }).lines).toEqual([])
  })
})
