import { describe, expect, it } from 'vitest'
import { bannerMessage, behindBanner, drawingChangesPhrase, heldByVenue, laterKey } from './venueHold'
import { NOT_HELD } from '../api/venueHoldApi'
import type { ProjectVenue } from '../api/projectApi'

const venue = (over: Partial<ProjectVenue> = {}): ProjectVenue => ({
  uuid: 'v', id: 9, name: 'Commemoration Hall', version: 3, installed: 5, behind: 'OUTPUT', ...over,
})

describe('behindBanner', () => {
  it('is held when the desk says the live show is held, whatever the project read says', () => {
    const hold = { projectId: 1, held: true, pinned: 3, installed: 5, runAgainst: false }
    expect(behindBanner(1, venue({ behind: 'NONE' }), hold)).toEqual({ kind: 'held', venue: 'Commemoration Hall', pinned: 3, installed: 5 })
  })

  it('is running against while the operator has lifted the hold', () => {
    const hold = { projectId: 1, held: false, pinned: 3, installed: 5, runAgainst: true }
    expect(behindBanner(1, venue(), hold)?.kind).toBe('running')
  })

  it('is a drawing-only banner when nothing changes output', () => {
    expect(behindBanner(1, venue({ behind: 'DRAWING' }), NOT_HELD)?.kind).toBe('drawing')
  })

  it('warns that a show behind on output, not live, will open held', () => {
    expect(behindBanner(2, venue(), { ...NOT_HELD, projectId: 1 })?.kind).toBe('output')
  })

  it('says nothing for a show on its latest, a standalone show, or an older desk', () => {
    expect(behindBanner(1, venue({ version: 5, behind: 'NONE' }), NOT_HELD)).toBeNull()
    expect(behindBanner(1, null, NOT_HELD)).toBeNull()
    expect(behindBanner(1, venue({ behind: undefined }), NOT_HELD)).toBeNull()
  })
})

describe('bannerMessage', () => {
  it('is the board’s copy', () => {
    expect(bannerMessage({ kind: 'held', venue: 'Commemoration Hall', pinned: 3, installed: 5 }, 'Spring Concert')).toBe(
      'Output held — this show is in Blind. It uses Commemoration Hall v3; the hall has been re-patched since (v5 is as hung). ' +
        'Its addresses would drive whatever is hung there now; only parked channels reach the rig.',
    )
    expect(bannerMessage({ kind: 'running', venue: 'Commemoration Hall', pinned: 3, installed: 5 }, 'Spring Concert')).toBe(
      "Running against v3. Live output uses the old house patch. v5's trigger and command channels stay guarded.",
    )
    expect(bannerMessage({ kind: 'drawing', venue: 'Commemoration Hall', pinned: 4, installed: 5 }, 'Panto 2027', 2)).toBe(
      'Commemoration Hall has moved on. Panto 2027 uses v4; v5 is the hall as hung — 2 drawing changes, nothing that changes output.',
    )
  })

  it('counts drawing changes', () => {
    expect(drawingChangesPhrase(1)).toBe('1 drawing change')
    expect(drawingChangesPhrase(undefined)).toBe('drawing changes')
  })
})

describe('heldByVenue', () => {
  it('is the live show held, only', () => {
    const hold = { projectId: 1, held: true, pinned: 3, installed: 5, runAgainst: false }
    expect(heldByVenue(hold, 1)).toBe(true)
    expect(heldByVenue(hold, 2)).toBe(false)
    expect(heldByVenue({ ...hold, held: false, runAgainst: true }, 1)).toBe(false)
  })

  it('keys Later by show and installed version', () => {
    expect(laterKey(4, 5)).toBe('venue-later:4:v5')
  })
})
