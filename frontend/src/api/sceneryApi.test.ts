import { describe, expect, it } from 'vitest'
import { parseProgrammerSceneryFrame, parseSceneryFrame, parseScenerySource } from './sceneryApi'

/**
 * `scenery.state`'s two additions (scenery-programmer plan D4, D12, P3): every entry's `source` and
 * the blind `staged` list. Both are optional for this client, so an older desk's frame — which
 * carries neither — has to parse exactly as it did.
 */
describe('parseSceneryFrame', () => {
  it('reads each entry’s source and the staged list, anchored at receipt', () => {
    const frame = parseSceneryFrame(
      {
        type: 'scenery.state',
        projectId: 6,
        elements: [
          {
            elementUuid: 'moon', state: { trimM: 3 }, from: { trimM: 7 }, startedAt: '2026-10-07 10:00:00.000Z',
            elapsedMs: 400, durationMs: 2000, source: { kind: 'programmer' },
          },
          {
            elementUuid: 'tabs', state: { open: 0 }, from: { open: 0 }, startedAt: '2026-10-07 10:00:00.000Z',
            elapsedMs: 0, durationMs: 0, source: { kind: 'cue', stackId: 2, cueId: 14, label: '14' },
          },
          {
            elementUuid: 'sofa', state: { visible: true }, from: { visible: false }, startedAt: '2026-10-07 10:00:00.000Z',
            source: { kind: 'programmerLook', lookId: 9, name: 'Night' },
          },
        ],
        staged: [{ elementUuid: 'tabs', state: { open: 1 }, from: { open: 0 }, elapsedMs: 100, durationMs: 4000 }],
      },
      10_000,
    )
    expect(frame.projectId).toBe(6)
    expect(frame.entries.moon).toEqual({
      elementUuid: 'moon', state: { trimM: 3 }, from: { trimM: 7 }, startedAtMs: 9_600, durationMs: 2000,
      source: { kind: 'programmer' },
    })
    expect(frame.entries.tabs.source).toEqual({ kind: 'cue', stackId: 2, cueId: 14, label: '14' })
    // The desk's Json drops defaults: a missing elapsed and duration are zero.
    expect(frame.entries.sofa).toMatchObject({ startedAtMs: 10_000, durationMs: 0, source: { kind: 'programmerLook', lookId: 9, name: 'Night' } })
    expect(frame.staged).toEqual({
      tabs: { elementUuid: 'tabs', state: { open: 1 }, from: { open: 0 }, startedAtMs: 9_900, durationMs: 4000 },
    })
  })

  it('parses an older desk’s frame, with neither source nor staged, exactly as before', () => {
    const frame = parseSceneryFrame(
      {
        type: 'scenery.state',
        projectId: 6,
        elements: [{ elementUuid: 'moon', state: { trimM: 3 }, from: { trimM: 7 }, elapsedMs: 0, durationMs: 0 }],
      },
      500,
    )
    expect(frame).toEqual({
      projectId: 6,
      entries: { moon: { elementUuid: 'moon', state: { trimM: 3 }, from: { trimM: 7 }, startedAtMs: 500, durationMs: 0 } },
    })
    expect('staged' in frame).toBe(false)
    expect('source' in frame.entries.moon).toBe(false)
  })

  it('drops a source kind it does not know rather than guessing at it', () => {
    expect(parseScenerySource({ kind: 'fly-floor', name: 'x' })).toBeUndefined()
    expect(parseScenerySource(null)).toBeUndefined()
    expect(parseScenerySource({ kind: 'set', stackId: 'two', name: 'Main' })).toEqual({ kind: 'set', name: 'Main' })
  })
})

describe('parseProgrammerSceneryFrame', () => {
  it('keeps each held element’s states in the order first held, and only well-typed ones', () => {
    expect(
      parseProgrammerSceneryFrame({
        type: 'programmer.sceneryState',
        projectId: 6,
        elements: [
          { elementUuid: 'moon', state: { trimM: 3, open: 'x' } },
          { elementUuid: 'tabs', state: { open: 0.5 } },
          'junk',
        ],
      }),
    ).toEqual({ projectId: 6, elements: [{ elementUuid: 'moon', state: { trimM: 3 } }, { elementUuid: 'tabs', state: { open: 0.5 } }] })
  })

  it('reads an absent list as nothing held', () => {
    expect(parseProgrammerSceneryFrame({ type: 'programmer.sceneryState' })).toEqual({ projectId: null, elements: [] })
  })
})
