import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { installRecordingFetch, installRelativeUrlRequest, templatesWs } from '@/test/backendMock'

// lightingApi opens a real WebSocket at import; mock it. This slice's bridges are **not** started
// on import (see `startTemplatesBridge`), so each test starts them itself.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { store } from './index'
import { restApi } from './restApi'
import { startTemplatesBridge, templatesApi } from './templates'
import type { TemplateSummary } from '@/api/templatesApi'

/**
 * The press log's client half: `templatePressed` **patches** the cached library rather than
 * invalidating it.
 *
 * That distinction is the whole reason the frame is keyed. A press happens at busking rate, and the
 * sibling `templateListChanged` bridge drops three caches — so reusing it would mean a refetch of
 * the whole library per chip press, behind an open picker.
 */
function row(over: Partial<TemplateSummary> = {}): TemplateSummary {
  return {
    id: 1,
    uuid: 'u1',
    name: 'Amber Key',
    notes: null,
    fadeDurationMs: null,
    family: 'COLOUR',
    isGeneric: true,
    kind: 'value',
    requiredEmitters: [],
    lastPressedAt: null,
    rows: [],
    effect: null,
    layerCount: 0,
    buskPageCount: 0,
    ...over,
  }
}

describe('the templatePressed bridge', () => {
  let fetchMock: ReturnType<typeof installRecordingFetch>

  beforeEach(() => {
    installRelativeUrlRequest()
    fetchMock = installRecordingFetch({
      'projects/1/templates/1/apply': { written: 2, skipped: [] },
      'projects/1/templates/2/apply': { written: 0, skipped: [{ fixtureKey: 'p', propertyName: 'c', reason: 'x' }] },
      'projects/1/templates/1/toggle': { action: 'applied', effectCount: 0, propertyMask: 'COLOUR' },
      'projects/1/templates/2/toggle': { action: 'removed', effectCount: 0, propertyMask: 'COLOUR' },
      'projects/1/templates?family=COLOUR': [row(), row({ id: 2, uuid: 'u2', name: 'Blue Wash' })],
      'projects/1/templates': [row(), row({ id: 2, uuid: 'u2', name: 'Blue Wash' })],
    })
    startTemplatesBridge()
  })

  afterEach(() => {
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
    templatesWs.pressed = null
    templatesWs.changed = null
  })

  function countRequestsTo(fragment: string): number {
    return fetchMock.mock.calls.filter((call) => (call[0] as Request).url.includes(fragment)).length
  }

  function cached(family?: 'COLOUR') {
    return templatesApi.endpoints.templateList.select({ projectId: 1, family })(store.getState())
      .data
  }

  it('writes the stamp into every cached list, whatever family each was filtered to', async () => {
    // `?family=` is a query argument, so the programmer's unfiltered list and `/templates?family=`
    // are two cache entries of one endpoint. A press seen through one has to move the other, or
    // the two disagree about the order of the same library.
    const all = store.dispatch(templatesApi.endpoints.templateList.initiate({ projectId: 1 }))
    const colour = store.dispatch(
      templatesApi.endpoints.templateList.initiate({ projectId: 1, family: 'COLOUR' }),
    )
    await all
    await colour
    const before = countRequestsTo('projects/1/templates')

    templatesWs.pressed?.({ templateId: 2, lastPressedAt: '2026-09-14T10:00:00.000Z' })

    expect(cached()?.find((t) => t.id === 2)?.lastPressedAt).toBe('2026-09-14T10:00:00.000Z')
    expect(cached('COLOUR')?.find((t) => t.id === 2)?.lastPressedAt).toBe('2026-09-14T10:00:00.000Z')
    // The point of the keyed frame: no network at all.
    expect(countRequestsTo('projects/1/templates')).toBe(before)

    all.unsubscribe()
    colour.unsubscribe()
  })

  it('leaves a template the entry does not hold alone rather than inserting it', async () => {
    const all = store.dispatch(templatesApi.endpoints.templateList.initiate({ projectId: 1 }))
    await all

    templatesWs.pressed?.({ templateId: 99, lastPressedAt: '2026-09-14T10:00:00.000Z' })

    expect(cached()).toHaveLength(2)
    all.unsubscribe()
  })

  it('patches the acting tab from a click that wrote something', async () => {
    const all = store.dispatch(templatesApi.endpoints.templateList.initiate({ projectId: 1 }))
    await all

    await store.dispatch(
      templatesApi.endpoints.applyTemplate.initiate({ projectId: 1, templateId: 1, targets: [] }),
    )
    expect(cached()?.find((t) => t.id === 1)?.lastPressedAt).not.toBeNull()

    all.unsubscribe()
  })

  it('does not patch from a click that reached no head', async () => {
    // The server applies the same rule, and the two must agree or the row shows a template the
    // desk has no record of having been pressed.
    const all = store.dispatch(templatesApi.endpoints.templateList.initiate({ projectId: 1 }))
    await all

    await store.dispatch(
      templatesApi.endpoints.applyTemplate.initiate({ projectId: 1, templateId: 2, targets: [] }),
    )
    expect(cached()?.find((t) => t.id === 2)?.lastPressedAt).toBeNull()

    all.unsubscribe()
  })

  it('patches a toggle on the way on and not on the way off', async () => {
    const all = store.dispatch(templatesApi.endpoints.templateList.initiate({ projectId: 1 }))
    await all

    await store.dispatch(
      templatesApi.endpoints.toggleTemplate.initiate({ projectId: 1, templateId: 1, targets: [] }),
    )
    expect(cached()?.find((t) => t.id === 1)?.lastPressedAt).not.toBeNull()

    await store.dispatch(
      templatesApi.endpoints.toggleTemplate.initiate({ projectId: 1, templateId: 2, targets: [] }),
    )
    expect(cached()?.find((t) => t.id === 2)?.lastPressedAt).toBeNull()

    all.unsubscribe()
  })
})
