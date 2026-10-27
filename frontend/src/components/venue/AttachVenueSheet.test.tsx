// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import type { ProjectSummary } from '@/api/projectApi'
import type { AttachReview, DetachedFrom, UpdateDecisionAnswer } from '@/api/venueApi'

/**
 * *Attach to venue…*'s sheet (venue-attach plan §5, §8 Session 3): it opens on the venue the show was
 * detached from at its installed version, says whether matching is three-way, sends each answer to the
 * desk — a decision's, an overlap's with its start channel — and holds *Attach* back on a blocker or an
 * unanswered overlap.
 */

type ReviewArgs = { projectId: number; venueId: number; to?: number; decisions: UpdateDecisionAnswer[] }

let projects: ProjectSummary[] = []
let reviewFor: (args: ReviewArgs) => AttachReview
/** An error the review query answers for these args, in place of a review it keeps drawing. */
let reviewErrorFor: (args: ReviewArgs) => unknown = () => undefined
let lastGood: AttachReview | undefined
const reviewArgs: ReviewArgs[] = []
const attachArgs: unknown[] = []
let attachResult: Promise<unknown> = Promise.resolve({})

vi.mock('@/store/projects', () => ({
  useProjectListQuery: () => ({ data: projects }),
}))
vi.mock('@/store/venues', () => ({
  useVenueAttachReviewQuery: (args: ReviewArgs, opts: { skip?: boolean }) => {
    if (opts.skip) return { data: undefined, isFetching: false, error: undefined }
    reviewArgs.push(args)
    const error = reviewErrorFor(args)
    // As RTK does: a refused read keeps the last review in `data`.
    if (error != null) return { data: lastGood, isFetching: false, error }
    lastGood = reviewFor(args)
    return { data: lastGood, isFetching: false, error: undefined }
  },
  useAttachVenueMutation: () => [
    (arg: unknown) => {
      attachArgs.push(arg)
      return { unwrap: () => attachResult }
    },
    { error: undefined, reset: () => {} },
  ],
}))

const { AttachVenueSheet } = await import('./AttachVenueSheet')

const ORIGIN: DetachedFrom = { venueUuid: 'tch', version: 7, venueId: 16, venueName: 'TCH venue' }

const project = (id: number, name: string, extra: Partial<ProjectSummary> = {}): ProjectSummary => ({
  id,
  name,
  description: null,
  isCurrent: false,
  kind: 'SHOW',
  ...extra,
})
const ALPHA = project(3, 'Alpha hall', { kind: 'VENUE', venueSummary: { version: 2 } })
const TCH = project(16, 'TCH venue', { kind: 'VENUE', venueSummary: { version: 8 } })
const EMPTY = project(5, 'Annex', { kind: 'VENUE', venueSummary: { version: null } })

const BASE: AttachReview = {
  projectId: 26,
  venueId: 16,
  venueName: 'TCH venue',
  to: 8,
  installed: 8,
  behind: 'NONE',
  runAgainst: false,
  origin: { venueUuid: 'tch', version: 7, status: 'USED', detail: 'Matched three ways against v7' },
  inherited: { PATCH: 54, UNIVERSE: 4 },
}

const IDENTITY = {
  id: 'identity:p1',
  kind: 'IDENTITY' as const,
  recordKind: 'PATCH' as const,
  show: { uuid: 'p1', name: 'Hex 7', key: 'hex-7' },
  venue: { uuid: 'v1', name: 'Hex 7', key: 'hex-07' },
  message: 'Your Hex 7 (hex-7) is not the venue’s Hex 7 (hex-07): key',
  answers: ['KEEP_MINE' as const, 'TAKE_VENUES' as const],
  answer: 'KEEP_MINE' as const,
  rename: {
    kind: 'PATCH' as const,
    from: 'hex-7',
    to: 'hex-07',
    show: { uuid: 'p1', name: 'Hex 7', key: 'hex-7' },
    venue: { uuid: 'v1', name: 'Hex 7', key: 'hex-07' },
    usedBy: [
      { kind: 'CUE', id: 1, name: 'Q1' },
      { kind: 'CUE', id: 2, name: 'Q2' },
      { kind: 'LOOK', id: 3, name: 'Warm' },
      { kind: 'PALETTE', id: 4, name: 'Amber' },
      { kind: 'BINDING', id: 5, name: 'Fader 1' },
    ],
  },
}

const OVERLAP = {
  id: 'overlap:a:b',
  kind: 'ADDRESS_OVERLAP',
  message: 'Your SMOKE 2 at 1-512 clashes with HAZER at 1-512',
  mine: { uuid: 'a', patchId: 1, key: 'smoke-2', name: 'SMOKE 2', address: '1-512', kind: 'OWN' },
  other: { uuid: 'b', patchId: 2, key: 'hazer', name: 'HAZER', address: '1-512', kind: 'VENUE' },
  answers: ['KEEP_BOTH' as const, 'READDRESS_MINE' as const, 'HIDE_VENUES' as const, 'REMOVE_MINE' as const],
  suggestedStartChannel: 480,
  suggestedAddress: '1-480',
}

function Where() {
  return <div data-testid="where">{useLocation().pathname}</div>
}

function draw(detachedFrom: DetachedFrom | null = ORIGIN, onOpenChange = vi.fn()) {
  projects = [ALPHA, EMPTY, TCH, project(26, 'TCH Test', { detachedFrom })]
  render(
    <MemoryRouter initialEntries={['/projects']}>
      <Routes>
        <Route
          path="*"
          element={
            <>
              <AttachVenueSheet open onOpenChange={onOpenChange} projectId={26} showName="TCH Test" />
              <Where />
            </>
          }
        />
      </Routes>
    </MemoryRouter>,
  )
  return onOpenChange
}

/** Past the review's debounce, so the answers so far have been asked. */
const settle = () => act(() => vi.advanceTimersByTime(300))

const attachButton = () => screen.getByRole('button', { name: /^Attach/ }) as HTMLButtonElement
const lastReviewArgs = () => reviewArgs[reviewArgs.length - 1]

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  reviewFor = (args) => ({ ...BASE, venueId: args.venueId, to: args.to ?? 8 })
  reviewErrorFor = () => undefined
  lastGood = undefined
  attachResult = Promise.resolve({})
})
afterEach(() => {
  vi.useRealTimers()
  cleanup()
  reviewArgs.length = 0
  attachArgs.length = 0
})

describe('the pick', () => {
  it('defaults to the venue the show was detached from, first and marked, at its installed version', () => {
    draw()
    const venues = within(screen.getByRole('radiogroup', { name: 'Venue' })).getAllByRole('radio')
    expect(venues.map((v) => v.getAttribute('data-attach-venue'))).toEqual(['16', '3', '5'])
    expect(venues[0].getAttribute('aria-checked')).toBe('true')
    expect(venues[0].textContent).toContain('Detached from here')
    expect(venues[2]).toBeDisabled()
    expect(venues[2].textContent).toContain('Nothing published')

    const versions = within(screen.getByRole('radiogroup', { name: 'Version' })).getAllByRole('radio')
    expect(versions.map((v) => v.textContent)).toEqual(['v8 · installed', 'v7 · the version TCH Test left'])
    expect(versions[0].getAttribute('aria-checked')).toBe('true')
    expect(lastReviewArgs()).toEqual({ projectId: 26, venueId: 16, to: 8, decisions: [] })
    expect(screen.getByText('Attach TCH Test to TCH venue v8')).toBeInTheDocument()
  })

  it('reviews the origin’s version when it is picked', () => {
    draw()
    fireEvent.click(screen.getByRole('radio', { name: /v7/ }))
    expect(lastReviewArgs()).toMatchObject({ venueId: 16, to: 7 })
    expect(attachButton().textContent).toBe('Attach to TCH venue v7')
  })

  it('offers the origin’s version again after another venue was looked at', () => {
    reviewFor = (args) =>
      args.venueId === 3
        ? { ...BASE, venueId: 3, venueName: 'Alpha hall', to: 2, installed: 2, origin: { ...BASE.origin!, status: 'OTHER_VENUE', detail: 'another venue' } }
        : { ...BASE, venueId: args.venueId, to: args.to ?? 8 }
    draw()
    fireEvent.click(screen.getByRole('radio', { name: /Alpha hall/ }))
    fireEvent.click(screen.getByRole('radio', { name: /TCH venue/ }))
    expect(within(screen.getByRole('radiogroup', { name: 'Version' })).getAllByRole('radio').map((v) => v.textContent)).toEqual([
      'v8 · installed',
      'v7 · the version TCH Test left',
    ])
  })

  it('names each venue with its version and mark', () => {
    draw()
    expect(screen.getByRole('radio', { name: 'TCH venue v8 Detached from here' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'Annex Nothing published' })).toBeDisabled()
    expect(screen.getByRole('radio', { name: 'v7 · the version TCH Test left' })).toBeInTheDocument()
  })

  it('opens on the first venue by name without an origin, and offers its installed version alone', () => {
    reviewFor = (args) => ({ ...BASE, venueId: args.venueId, venueName: 'Alpha hall', to: 2, installed: 2, origin: null })
    draw(null)
    const venues = within(screen.getByRole('radiogroup', { name: 'Venue' })).getAllByRole('radio')
    expect(venues.map((v) => v.getAttribute('data-attach-venue'))).toEqual(['3', '5', '16'])
    expect(venues[0].getAttribute('aria-checked')).toBe('true')
    expect(screen.queryByText('Detached from here')).toBeNull()
    expect(within(screen.getByRole('radiogroup', { name: 'Version' })).getAllByRole('radio').map((v) => v.textContent)).toEqual([
      'v2 · installed',
    ])
  })
})

describe('the matching line', () => {
  const line = () => document.querySelector('[data-attach-matching]')!.textContent

  it('says three-way against the version the show left', () => {
    draw()
    expect(line()).toBe('Using v7, the version TCH Test left.')
  })

  it('says every venue record the show lacks comes in, with no origin', () => {
    reviewFor = (args) => ({ ...BASE, venueId: args.venueId, to: 2, installed: 2, venueName: 'Alpha hall', origin: null })
    draw(null)
    expect(line()).toBe('No record of where this show came from: every venue record the show lacks comes in.')
  })

  it('says why, for an origin version this desk lacks', () => {
    reviewFor = (args) => ({
      ...BASE,
      venueId: args.venueId,
      origin: {
        venueUuid: 'tch',
        version: 7,
        status: 'MISSING',
        detail: "This show was detached from TCH venue v7, which this desk doesn't have: matching is two-way, and every venue record the show lacks comes in",
      },
    })
    draw()
    expect(line()).toBe(
      "This show was detached from TCH venue v7, which this desk doesn't have: matching is two-way, and every venue record the show lacks comes in.",
    )
    // The version the desk lacks is no longer offered.
    expect(within(screen.getByRole('radiogroup', { name: 'Version' })).getAllByRole('radio').map((v) => v.textContent)).toEqual([
      'v8 · installed',
    ])
  })

  it('says why, for an origin in another venue', () => {
    reviewFor = (args) => ({
      ...BASE,
      venueId: args.venueId,
      venueName: 'Alpha hall',
      to: 2,
      installed: 2,
      origin: {
        venueUuid: 'tch',
        version: 7,
        status: 'OTHER_VENUE',
        detail: 'This show was detached from another venue (TCH venue): matching is two-way, and every venue record the show lacks comes in',
      },
    })
    draw()
    fireEvent.click(screen.getByRole('radio', { name: /Alpha hall/ }))
    expect(lastReviewArgs()).toMatchObject({ venueId: 3, to: 2 })
    expect(line()).toBe(
      'This show was detached from another venue (TCH venue): matching is two-way, and every venue record the show lacks comes in.',
    )
  })
})

describe('the review', () => {
  it('says attaching changes no output, and what comes from the venue', () => {
    draw()
    expect(within(screen.getByRole('region', { name: 'Output' })).getByText('Attaching changes no output.')).toBeInTheDocument()
    expect(document.querySelector('[data-attach-inherited]')!.textContent).toBe("54 fixtures, 4 universes: the venue's replace yours.")
  })

  it('lists output changes first, then drawing only', () => {
    reviewFor = (args) => ({
      ...BASE,
      venueId: args.venueId,
      output: [{ kind: 'PATCH', detail: 'The venue re-addressed Shehds 1 to 2-001' }],
      drawing: [{ kind: 'RIGGING', detail: 'The venue changed its rigging LX1' }],
    })
    draw()
    const output = screen.getByRole('region', { name: 'Output' })
    expect(within(output).queryByText('Attaching changes no output.')).toBeNull()
    const groups = within(output).getAllByRole('group').map((g) => g.getAttribute('aria-label'))
    expect(groups).toEqual(['Changes output', 'Drawing only'])
    expect(within(output).getByText('The venue re-addressed Shehds 1 to 2-001')).toBeInTheDocument()
  })

  it('lists a group that differs only in order as a drawing change, asking nothing (group-order plan call B)', () => {
    reviewFor = (args) => ({
      ...BASE,
      venueId: args.venueId,
      drawing: [{ kind: 'GROUP', detail: "Your group Front wash becomes the venue's, chased in your order" }],
    })
    draw()
    const drawing = screen.getByRole('group', { name: 'Drawing only' })
    expect(within(drawing).getByText("Your group Front wash becomes the venue's, chased in your order")).toBeInTheDocument()
    expect(attachButton()).toBeEnabled()
  })

  it('says a show attached behind on output runs against its pin while it is on the rig', () => {
    reviewFor = (args) => ({ ...BASE, venueId: args.venueId, to: 7, installed: 8, behind: 'OUTPUT', runAgainst: true })
    draw()
    fireEvent.click(screen.getByRole('radio', { name: /v7/ }))
    expect(document.querySelector('[data-attach-behind="OUTPUT"]')!.textContent).toContain('carries on, run against v7, rather than going dark')
  })
})

describe('answers', () => {
  it('a decision’s answer reaches the review and the attach, with its rename’s reach', async () => {
    reviewFor = (args) => {
      const taken = args.decisions.find((d) => d.id === IDENTITY.id)
      return { ...BASE, venueId: args.venueId, decisions: [{ ...IDENTITY, answer: taken?.answer ?? 'KEEP_MINE', decided: taken != null }] }
    }
    draw()
    expect(document.querySelector(`[data-attach-reach="${IDENTITY.id}"]`)!.textContent).toBe(
      "Take venue's renames hex-7 → hex-07 in 2 cues, 1 Look, 1 palette, 1 binding.",
    )
    const group = screen.getByRole('radiogroup', { name: 'Answer: Hex 7' })
    expect(within(group).getByRole('radio', { name: 'Keep mine' }).getAttribute('aria-checked')).toBe('true')

    fireEvent.click(within(group).getByRole('radio', { name: "Take venue's" }))
    // Drawn at once, asked a moment later: a run of presses is one dry run.
    expect(within(screen.getByRole('radiogroup', { name: 'Answer: Hex 7' })).getByRole('radio', { name: "Take venue's" }).getAttribute('aria-checked')).toBe('true')
    expect(lastReviewArgs().decisions).toEqual([])
    expect(attachButton()).toBeDisabled()
    await settle()
    expect(lastReviewArgs().decisions).toEqual([{ id: IDENTITY.id, answer: 'TAKE_VENUES' }])
    expect(within(screen.getByRole('radiogroup', { name: 'Answer: Hex 7' })).getByRole('radio', { name: "Take venue's" }).getAttribute('aria-checked')).toBe('true')

    await act(async () => fireEvent.click(attachButton()))
    expect(attachArgs).toEqual([{ projectId: 26, venueId: 16, to: 8, decisions: [{ id: IDENTITY.id, answer: 'TAKE_VENUES' }] }])
  })

  it('an overlap’s Re-address mine reaches the attach with the start channel the review last showed', async () => {
    // The desk picks the free start for a dry run; what it suggests then moves with the other answers.
    reviewFor = (args) => {
      const moved = args.decisions.some((d) => d.id === OVERLAP.id)
      return { ...BASE, venueId: args.venueId, overlaps: [moved ? { ...OVERLAP, suggestedStartChannel: 450, suggestedAddress: '1-450' } : OVERLAP] }
    }
    draw()
    fireEvent.click(screen.getByRole('radio', { name: 'Re-address mine → 1-480' }))
    await settle()
    expect(lastReviewArgs().decisions).toEqual([{ id: OVERLAP.id, answer: 'READDRESS_MINE' }])
    expect(screen.getByRole('radio', { name: 'Re-address mine → 1-450' }).getAttribute('aria-checked')).toBe('true')
    await act(async () => fireEvent.click(attachButton()))
    expect(attachArgs).toEqual([
      { projectId: 26, venueId: 16, to: 8, decisions: [{ id: OVERLAP.id, answer: 'READDRESS_MINE', startChannel: 450 }] },
    ])
  })

  it('drops an answer the desk no longer takes, and reads again', async () => {
    reviewFor = (args) => ({ ...BASE, venueId: args.venueId, overlaps: [OVERLAP] })
    reviewErrorFor = (args) =>
      args.decisions.length > 0
        ? { status: 400, data: { error: `'READDRESS_MINE' does not answer '${OVERLAP.id}': it takes KEEP_BOTH | REMOVE_MINE`, code: 'ATTACH_INVALID' } }
        : undefined
    draw()
    fireEvent.click(screen.getByRole('radio', { name: 'Re-address mine → 1-480' }))
    await settle()
    await settle()
    expect(lastReviewArgs().decisions).toEqual([])
    expect(screen.getByRole('radio', { name: 'Re-address mine → 1-480' }).getAttribute('aria-checked')).toBe('false')
    expect(screen.queryByText('The review could not be read')).toBeNull()
  })

  it('without an origin, Hide in this show answers the incoming record’s hide', async () => {
    reviewFor = (args) => ({
      ...BASE,
      venueId: args.venueId,
      to: args.to ?? 2,
      origin: null,
      incoming: [{ kind: 'PATCH', venue: { uuid: 'h', name: 'House', key: 'house' }, detail: "The venue's fixture House comes in", reason: 'NO_ORIGIN' }],
      decisions: [
        {
          id: 'hide:PATCH:h',
          kind: 'HIDE',
          recordKind: 'PATCH',
          venue: { uuid: 'h', name: 'House', key: 'house' },
          message: "The venue's fixture House comes in: hide it in this show?",
          answers: ['TAKE_VENUES', 'HIDE_VENUES'],
          answer: 'TAKE_VENUES',
        },
      ],
    })
    draw(null)
    const incoming = screen.getByRole('group', { name: 'Comes in from the venue' })
    fireEvent.click(within(incoming).getByRole('button', { name: 'Hide in this show' }))
    await settle()
    expect(lastReviewArgs().decisions).toEqual([{ id: 'hide:PATCH:h', answer: 'HIDE_VENUES' }])
  })

  it('draws no toggle on a record whose hide the desk refused, and names one hidden by its toggle once', () => {
    const house = { uuid: 'h', name: 'House', key: 'house' }
    const hideOf = (uuid: string, answer: 'TAKE_VENUES' | 'HIDE_VENUES') => ({
      id: `hide:PATCH:${uuid}`,
      kind: 'HIDE' as const,
      recordKind: 'PATCH' as const,
      venue: { uuid, name: uuid, key: uuid },
      message: 'hide it?',
      answers: ['TAKE_VENUES' as const, 'HIDE_VENUES' as const],
      answer,
    })
    reviewFor = (args) => ({
      ...BASE,
      venueId: args.venueId,
      to: args.to ?? 2,
      origin: null,
      incoming: [{ kind: 'PATCH', venue: house, detail: "The venue's fixture House comes in: it can't be hidden, Q1 names it", reason: 'HIDE_REFUSED' }],
      hides: [{ kind: 'PATCH', venue: { uuid: 'w', name: 'Works', key: 'works' }, detail: 'Hidden in this show', reason: 'ANSWERED' }],
      decisions: [{ ...hideOf('h', 'HIDE_VENUES'), venue: house }, hideOf('w', 'HIDE_VENUES')],
    })
    draw(null)
    const incoming = screen.getByRole('group', { name: 'Comes in from the venue' })
    expect(within(incoming).queryByRole('button', { name: 'Hide in this show' })).toBeNull()
    const hidden = screen.getByRole('group', { name: 'Hidden in this show' })
    expect(within(hidden).getByText("The venue's fixture Works (works)")).toBeInTheDocument()
    expect(within(hidden).getByRole('button', { name: 'Hide in this show' }).getAttribute('aria-pressed')).toBe('true')
  })

  it('stays open while the attach runs, and draws its refusal', async () => {
    let refuse: (e: unknown) => void = () => {}
    attachResult = new Promise((_, reject) => (refuse = reject))
    attachResult.catch(() => {})
    const onOpenChange = draw()
    await act(async () => fireEvent.click(attachButton()))
    expect(screen.getByRole('status').textContent).toBe('Attaching TCH Test to TCH venue v8…')
    fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' })
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
    await act(async () => refuse({ status: 409, data: { error: 'Blocked', code: 'ATTACH_BLOCKED' } }))
    expect(screen.queryByRole('status')).toBeNull()
    expect(screen.getByTestId('where').textContent).toBe('/projects')
  })

  it('closes on the show’s home once the attach lands', async () => {
    const onOpenChange = draw()
    await act(async () => fireEvent.click(attachButton()))
    expect(onOpenChange).toHaveBeenCalledWith(false)
    expect(screen.getByTestId('where').textContent).toBe('/projects/26')
  })
})

describe('Attach is held back', () => {
  it('while a blocker stands', () => {
    reviewFor = (args) => ({
      ...BASE,
      venueId: args.venueId,
      blockers: [{ kind: 'PATCH', detail: 'Your fixture Hex 9 has its key hex-9, which the venue’s holds — rename yours first', reason: 'KEY_CLASH' }],
    })
    draw()
    expect(attachButton()).toBeDisabled()
    expect(screen.getByRole('group', { name: "Can't be attached" })).toBeInTheDocument()
    expect(document.querySelector('[data-attach-refusal]')!.textContent).toBe("Change the show first: a clash can't be kept.")
  })

  it('while an overlap is unanswered', async () => {
    reviewFor = (args) => ({ ...BASE, venueId: args.venueId, overlaps: [OVERLAP] })
    draw()
    expect(attachButton()).toBeDisabled()
    expect(document.querySelector('[data-attach-refusal]')!.textContent).toBe('1 address overlap still needs a decision.')
    fireEvent.click(screen.getByRole('radio', { name: 'Keep both' }))
    await settle()
    expect(attachButton()).not.toBeDisabled()
  })

  it('while the review cannot be read with the answers given', async () => {
    reviewFor = (args) => ({ ...BASE, venueId: args.venueId, decisions: [IDENTITY] })
    reviewErrorFor = (args) => (args.decisions.length > 0 ? { status: 500, data: { error: 'Database busy' } } : undefined)
    draw()
    fireEvent.click(screen.getByRole('radio', { name: "Take venue's" }))
    await settle()
    expect(screen.getByText('The review could not be read')).toBeInTheDocument()
    expect(attachButton()).toBeDisabled()
  })
})
