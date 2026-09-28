// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, render } from '@testing-library/react'
import type { ProgrammerLayer } from '@/api/programmerWsApi'

/**
 * The provider's two non-obvious behaviours, both of which self-heal quietly enough that a
 * regression would show up as "the grid went back to Output for no reason" rather than as a crash:
 * `focusLayer`'s membership guard, and the fallback when the focused layer leaves the stack.
 *
 * `ProgrammerPage.test.tsx` mounts the real provider, but only exercises the landing scope and the
 * Output/Local switch — neither of which touches the layer list at all.
 */

const mocks = vi.hoisted(() => ({ layers: undefined as ProgrammerLayer[] | undefined }))
vi.mock('@/store/programmer', () => ({
  useProgrammerLayersQuery: () => ({ data: mocks.layers }),
}))

import {
  ProgrammerScopeProvider,
  useProgrammerScope,
  useProgrammerScopeActions,
  type ProgrammerScope,
  type ProgrammerScopeActions,
} from './ProgrammerScope'

function layer(layerId: number): ProgrammerLayer {
  return {
    layerId,
    source: { kind: 'LOOK', id: layerId, uuid: `u${layerId}`, name: `Look ${layerId}` },
    sortOrder: layerId,
    enabled: true,
    targets: [],
    blendMode: 'OVERRIDE',
    amount: 1,
    stomp: false,
  }
}

/** Every scope and every actions object the probe has been handed, newest last. */
const scopes: (ProgrammerScope | null)[] = []
const actionsSeen: (ProgrammerScopeActions | null)[] = []

function Probe() {
  scopes.push(useProgrammerScope())
  actionsSeen.push(useProgrammerScopeActions())
  return null
}

function draw() {
  const view = render(
    <ProgrammerScopeProvider>
      <Probe />
    </ProgrammerScopeProvider>,
  )
  return {
    ...view,
    scope: () => scopes[scopes.length - 1],
    actions: () => actionsSeen[actionsSeen.length - 1]!,
    redraw: () =>
      view.rerender(
        <ProgrammerScopeProvider>
          <Probe />
        </ProgrammerScopeProvider>,
      ),
  }
}

afterEach(() => {
  cleanup()
  scopes.length = 0
  actionsSeen.length = 0
  mocks.layers = undefined
})

describe('ProgrammerScopeProvider', () => {
  it('lands on Local — what the operator set, not the cook', () => {
    mocks.layers = [layer(1)]
    const view = draw()
    expect(view.scope()).toEqual({ kind: 'local' })
  })

  it('refuses a layerId the programmer stack does not hold, and says so', () => {
    // The guard that bites: `ProvenanceEntry.layerId` names a *cue's* layer too, so a grid cell lit
    // by a cue's Warm Wash reports an id that means nothing in this stack. Accepting it would point
    // the grid at a layer that does not exist; the boolean is how the caller knows to do nothing.
    mocks.layers = [layer(1)]
    const view = draw()

    let accepted: boolean | undefined
    act(() => {
      accepted = view.actions().focusLayer(99)
    })

    expect(accepted).toBe(false)
    expect(view.scope()).toEqual({ kind: 'local' })
  })

  it('refuses every layerId while the stack is still loading', () => {
    // `undefined` is "we don't know yet", not "empty" — focusing on a guess would land the grid on a
    // layer the fallback effect then bounces it off again.
    const view = draw()

    let accepted: boolean | undefined
    act(() => {
      accepted = view.actions().focusLayer(1)
    })

    expect(accepted).toBe(false)
    expect(view.scope()).toEqual({ kind: 'local' })
  })

  it('accepts a layerId the stack holds and points the grid at it', () => {
    mocks.layers = [layer(1), layer(2)]
    const view = draw()

    let accepted: boolean | undefined
    act(() => {
      accepted = view.actions().focusLayer(2)
    })

    expect(accepted).toBe(true)
    expect(view.scope()).toEqual({ kind: 'layer', layerId: 2 })
  })

  it('falls back to Output when the focused layer leaves the stack', () => {
    // The programmer is shared and the stack arrives as a broadcast: a second desk can remove the
    // layer this grid is pointed at. Output, not Local, because the operator was looking at the
    // composition rather than at their own values.
    mocks.layers = [layer(1), layer(2)]
    const view = draw()
    act(() => {
      view.actions().focusLayer(2)
    })
    expect(view.scope()).toEqual({ kind: 'layer', layerId: 2 })

    mocks.layers = [layer(1)]
    act(() => {
      view.redraw()
    })

    expect(view.scope()).toEqual({ kind: 'output' })
  })

  it('leaves the scope alone while the stack is unknown', () => {
    // An in-flight refetch must not read as a removal, or every layer scope would bounce to Output
    // on reconnect.
    mocks.layers = [layer(1)]
    const view = draw()
    act(() => {
      view.actions().focusLayer(1)
    })

    mocks.layers = undefined
    act(() => {
      view.redraw()
    })

    expect(view.scope()).toEqual({ kind: 'layer', layerId: 1 })
  })

  it('keeps the actions object identity-stable across a scope change', () => {
    // The whole point of the split contexts: the actions object is read by every visible grid row,
    // and a fresh setter per scope change would re-render all of them on unrelated scope traffic.
    mocks.layers = [layer(1)]
    const view = draw()
    const before = view.actions()

    act(() => {
      view.actions().setScope({ kind: 'output' })
    })
    act(() => {
      view.actions().focusLayer(1)
    })

    expect(view.scope()).toEqual({ kind: 'layer', layerId: 1 })
    expect(view.actions()).toBe(before)
    expect(new Set(actionsSeen).size).toBe(1)
  })

  it('holds the same scope object when set to an equal scope', () => {
    mocks.layers = [layer(1)]
    const view = draw()
    act(() => {
      view.actions().setScope({ kind: 'output' })
    })
    const before = view.scope()

    act(() => {
      view.actions().setScope({ kind: 'output' })
    })

    expect(view.scope()).toBe(before)
  })
})
