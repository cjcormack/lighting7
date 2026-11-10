// @vitest-environment jsdom
import { Provider } from 'react-redux'
import { DndContext } from '@dnd-kit/core'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { store } from '@/store'
import { applyServerRunState } from '@/store/runnerSlice'
import type { BuskPad } from '@/api/buskApi'
import type { PaletteSummary } from '@/api/palettesApi'
import type { CueStack } from '@/api/cueStacksApi'
import { BuskPadButton } from './BuskPad'
import { assignTargetNow, closeAssignToSurface } from '@/components/surfaces/AssignToSurface'

/**
 * The one pad, and the two faces inside it.
 *
 * A `DndContext` is required because the pad registers itself as both a draggable and a droppable —
 * it does not create one (the app's single context is the whole point), so the test supplies one.
 */

const AT = { row: 0, column: 0, bank: 0, pad: 0 }

function palettePad(overrides: Partial<PaletteSummary> = {}): BuskPad {
  return {
    id: 1,
    uuid: 'p1',
    kind: 'PALETTE',
    palette: {
      id: 7,
      uuid: 't7',
      name: 'Amber Key',
      notes: 'the warm one',
      fadeDurationMs: null,
      family: 'COLOUR',
      isGeneric: true,
      kind: 'value',
      rows: [{ targetType: 'deferred', targetKey: '', attribute: 'colour', value: '#FF9D4A' }],
      fx: null,
      layerCount: 0,
      ...overrides,
    } as PaletteSummary,
  }
}

const cuePad: BuskPad = {
  id: 2,
  uuid: 'p2',
  kind: 'CUE',
  cue: {
    id: 12,
    uuid: 'c12',
    name: 'Verse 2',
    entries: [{ stackId: 3, stackName: 'Main Show', entryId: 112, cueNumber: '12' }],
  },
}

function draw(
  pad: BuskPad,
  props: Partial<React.ComponentProps<typeof BuskPadButton>> = {},
) {
  return render(
    <Provider store={store}>
      <DndContext>
        <BuskPadButton
          pad={pad}
          at={AT}
          presence="none"
          cueRing={null}
          editing={false}
          onPress={() => {}}
          onRemove={() => {}}
          onInspect={() => {}}
          onPickUp={() => {}}
          {...props}
        />
      </DndContext>
    </Provider>,
  )
}

afterEach(cleanup)

describe('a palette or Look pad', () => {
  it('draws its name, its detail line and its colour', () => {
    const { container } = draw(palettePad())
    expect(screen.getByText('Amber Key')).toBeTruthy()
    expect(screen.getByText('Colour')).toBeTruthy()
    expect(container.querySelector('[style*="rgb(255, 157, 74)"]')).toBeTruthy()
  })

  it('keeps the notes off the face and in the tooltip', () => {
    draw(palettePad())
    expect(screen.queryByText('the warm one')).toBeNull()
    expect(screen.getByRole('button').getAttribute('title')).toBe('the warm one')
  })

  it('climbs the presence ladder', () => {
    const { container: none } = draw(palettePad(), { presence: 'none' })
    expect(none.querySelector('button')!.className).toContain('bg-card')
    expect(none.querySelectorAll('.rounded-full')).toHaveLength(0)
    cleanup()

    const { container: some } = draw(palettePad(), { presence: 'some' })
    expect(some.querySelector('button')!.className).toContain('bg-primary/10')
    expect(some.querySelector('.bg-primary\\/50')).toBeTruthy()
    cleanup()

    const { container: all } = draw(palettePad(), { presence: 'all' })
    expect(all.querySelector('button')!.className).toContain('ring-primary/50')
    expect(all.querySelector('button')!.getAttribute('aria-pressed')).toBe('true')
  })

  it('marks an effect palette with the wave rather than a swatch', () => {
    const { container } = draw(
      palettePad({
        kind: 'fx',
        rows: [],
        fx: {
          fxType: 'Colour Pulse',
          family: 'colour',
          cycle: 0.5,
          blendMode: 'HTP',
          order: 'ALL',
          parameters: {},
          timingSource: 'BEAT',
        },
      }),
    )
    expect(container.querySelector('.lucide-audio-waveform')).toBeTruthy()
    expect(container.querySelector('[style*="background"]')).toBeNull()
  })
})

describe('a cue pad', () => {
  it('draws the number, the name and the stack', () => {
    draw(cuePad)
    expect(screen.getByText('12')).toBeTruthy()
    expect(screen.getByText('Verse 2')).toBeTruthy()
    expect(screen.getByText('Main Show')).toBeTruthy()
  })

  // D11: a loose hold is solid and pressed; live only in a stack is an outline, not pressed.
  it('draws a loose hold solid and pressed', () => {
    const { container } = draw(cuePad, { cueRing: 'loose' })
    const button = container.querySelector('button')!
    expect(button.className).toContain('bg-green-500/25')
    expect(button.getAttribute('aria-pressed')).toBe('true')
  })

  it('draws a cue live only in a stack as an outline, not pressed', () => {
    const { container } = draw(cuePad, { cueRing: 'stack' })
    const button = container.querySelector('button')!
    expect(button.className).toContain('border-green-500/80')
    expect(button.className).not.toContain('bg-green-500/25')
    expect(button.getAttribute('aria-pressed')).toBe('false')
  })

  it('draws an off cue with no ring', () => {
    const { container } = draw(cuePad)
    const button = container.querySelector('button')!
    expect(button.className).not.toContain('border-green-500')
    expect(button.getAttribute('aria-pressed')).toBe('false')
  })
})

describe('pressing', () => {
  it('presses on a click', () => {
    const onPress = vi.fn()
    draw(palettePad(), { onPress })
    const pad = screen.getByRole('button')
    fireEvent.pointerDown(pad, { clientX: 0, clientY: 0 })
    fireEvent.pointerUp(pad)
    expect(onPress).toHaveBeenCalledTimes(1)
  })

  it('does not press while editing, and offers a cross instead', () => {
    const onPress = vi.fn()
    const onRemove = vi.fn()
    draw(palettePad(), { onPress, onRemove, editing: true })
    const pad = screen.getByTitle('Amber Key')
    fireEvent.pointerDown(pad, { clientX: 0, clientY: 0 })
    fireEvent.pointerUp(pad)
    expect(onPress).not.toHaveBeenCalled()

    fireEvent.click(screen.getByLabelText('Remove Amber Key'))
    expect(onRemove).toHaveBeenCalledTimes(1)
  })

  it('offers no cross outside edit mode', () => {
    draw(palettePad())
    expect(screen.queryByLabelText('Remove Amber Key')).toBeNull()
  })
})

/**
 * The hold menu (multi-screen plan §3.5).
 *
 * The hold used to navigate straight to the library. It opens a **menu** now, because the hand
 * needed a door on a pad and a hold cannot mean two things — so *Pick up* is first and *View* is
 * the old behaviour, one item down.
 */
describe('the hold menu', () => {
  /** A hold, as `useLongPress` sees it: down, wait past the delay, and the menu opens under it. */
  function hold(pad: HTMLElement) {
    vi.useFakeTimers()
    fireEvent.pointerDown(pad, { clientX: 5, clientY: 5 })
    act(() => {
      vi.advanceTimersByTime(600)
    })
    vi.useRealTimers()
  }

  it('names the tap’s verb first, then Apply as values, Pick up and View', () => {
    draw(palettePad(), { onApplyValues: () => {} })
    hold(screen.getByRole('button'))
    const items = screen.getAllByRole('menuitem').map((i) => i.textContent)
    expect(items).toEqual(['Put on', 'Apply as values', 'Pick up', 'Assign to surface…', 'View'])
  })

  it('opens Assign to surface… on the pad itself', () => {
    draw(palettePad(), { onApplyValues: () => {} })
    hold(screen.getByRole('button'))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Assign to surface…' }))
    expect(assignTargetNow()?.subjects).toEqual([{ type: 'record', kind: 'PAD', uuid: 'p1' }])
    closeAssignToSurface()
  })

  it('says *Take off these heads* when the pad covers them all', () => {
    draw(palettePad(), { presence: 'all', onApplyValues: () => {} })
    hold(screen.getByRole('button'))
    expect(screen.getAllByRole('menuitem')[0]).toHaveTextContent('Take off these heads')
  })

  it('applies values from the menu and from ⌥click, and presses nothing', () => {
    const onApplyValues = vi.fn()
    const onPress = vi.fn()
    draw(palettePad(), { onApplyValues, onPress })
    const pad = screen.getByRole('button')
    fireEvent.pointerDown(pad, { altKey: true, clientX: 0, clientY: 0 })
    fireEvent.pointerUp(pad)
    expect(onApplyValues).toHaveBeenCalledTimes(1)
    hold(pad)
    fireEvent.click(screen.getByText('Apply as values'))
    expect(onApplyValues).toHaveBeenCalledTimes(2)
    expect(onPress).not.toHaveBeenCalled()
  })

  it('never presses on a right- or middle-button release, whatever follows', () => {
    // Windows sends `contextmenu` after the release, so a right-click pressed the pad first.
    const onPress = vi.fn()
    const onApplyValues = vi.fn()
    draw(palettePad(), { onPress, onApplyValues })
    const pad = screen.getByRole('button')
    for (const button of [2, 1]) {
      fireEvent.pointerDown(pad, { button, clientX: 0, clientY: 0 })
      fireEvent.pointerUp(pad, { button })
    }
    expect(onPress).not.toHaveBeenCalled()
    expect(onApplyValues).not.toHaveBeenCalled()
  })

  it('names both ways when nothing is selected on a per-fixture pad — it lands on its own heads', () => {
    draw(palettePad({ isGeneric: false }), { onApplyValues: () => {}, hasSelection: false })
    hold(screen.getByRole('button'))
    expect(screen.getAllByRole('menuitem')[0]).toHaveTextContent('Put on or take off its own heads')
  })

  it('asks for a selection on a generic pad with nothing selected — it has no heads of its own', () => {
    draw(palettePad(), { onApplyValues: () => {}, hasSelection: false })
    hold(screen.getByRole('button'))
    expect(screen.getAllByRole('menuitem')[0]).toHaveTextContent('Put on — select fixtures first')
  })

  it('offers a cue pad only Pick up and View — a cue is playback', () => {
    draw(cuePad, { onApplyValues: () => {} })
    hold(screen.getByRole('button'))
    expect(screen.getAllByRole('menuitem').map((i) => i.textContent)).toEqual(['Pick up', 'Assign to surface…', 'View'])
  })

  it('picks the record up, and leaves the pad where it is', () => {
    const onPickUp = vi.fn()
    const onPress = vi.fn()
    draw(palettePad(), { onPickUp, onPress })
    hold(screen.getByRole('button'))
    fireEvent.click(screen.getByText('Pick up'))
    expect(onPickUp).toHaveBeenCalledTimes(1)
    // A hold is not a press: the pad must not fire on the way into its own menu.
    expect(onPress).not.toHaveBeenCalled()
  })

  it('keeps View as the hold’s old behaviour', () => {
    const onInspect = vi.fn()
    draw(palettePad(), { onInspect })
    hold(screen.getByRole('button'))
    fireEvent.click(screen.getByText('View'))
    expect(onInspect).toHaveBeenCalledTimes(1)
  })

  it('offers View on a loose cue pad too — it opens the Cues table filtered to it', () => {
    const onInspect = vi.fn()
    draw({ ...cuePad, cue: { ...cuePad.cue!, entries: [] } }, { onInspect })
    hold(screen.getByRole('button'))
    fireEvent.click(screen.getByText('View'))
    expect(onInspect).toHaveBeenCalledTimes(1)
  })

  it('draws no menu while editing — there the pad is a drag handle', () => {
    draw(palettePad(), { editing: true })
    fireEvent.contextMenu(screen.getByTitle('Amber Key'))
    expect(screen.queryByRole('menuitem')).toBeNull()
  })
})

/**
 * A stack pad (lighting7 busk-stack plan D9, §4): taller, three lines — the stack, the live cue and
 * the next — solid green while it runs, *GO starts Q1 …* while stopped, *no cues* when empty, and a
 * menu of GO, Back, Release and View with no Pick up.
 */
describe('a stack pad', () => {
  const stackPad: BuskPad = { id: 5, uuid: 'p5', kind: 'CUE_STACK', cueStack: { id: 9, uuid: 's9', name: 'Song 3' } }

  function row(entryId: number, id: number, cueNumber: string, name: string) {
    return { entryId, id, cueType: 'STANDARD', cueNumber, name, fadeDurationMs: 0, sortOrder: entryId } as unknown as CueStack['cues'][number]
  }

  function stack(overrides: Partial<CueStack> = {}): CueStack {
    return {
      id: 9,
      name: 'Song 3',
      loop: false,
      sortOrder: 4,
      type: 'STACK',
      cues: [row(1, 11, '1', 'Bow'), row(2, 12, '2', 'Chorus'), row(3, 13, '3', 'Bridge')],
      activeCueId: null,
      activeEntryId: null,
      nextCueId: null,
      nextEntryId: null,
      inRunningOrder: false,
      releaseAtEnd: true,
      ...overrides,
    } as CueStack
  }

  function hold(pad: HTMLElement) {
    vi.useFakeTimers()
    fireEvent.pointerDown(pad, { clientX: 5, clientY: 5 })
    act(() => {
      vi.advanceTimersByTime(600)
    })
    vi.useRealTimers()
  }

  it('draws the cue a tap starts on while stopped, and is not pressed', () => {
    draw(stackPad, { stack: stack() })
    expect(screen.getByText('Song 3')).toBeTruthy()
    expect(screen.getByText('— stopped')).toBeTruthy()
    expect(screen.getByText('GO starts Q1 Bow')).toBeTruthy()
    expect(screen.getByRole('button')).toHaveAttribute('aria-pressed', 'false')
  })

  it('draws the live cue and the next while running, solid and pressed', () => {
    draw(stackPad, { stack: stack({ activeCueId: 12, activeEntryId: 2, nextCueId: 13, nextEntryId: 3 }) })
    expect(screen.getByText('Q2 Chorus')).toBeTruthy()
    expect(screen.getByText('→ Q3 Bridge')).toBeTruthy()
    const pad = screen.getByRole('button')
    expect(pad).toHaveAttribute('aria-pressed', 'true')
    expect(pad.className).toContain('bg-green-500/25')
  })

  it('says a Release stack’s last cue lets it go', () => {
    draw(stackPad, { stack: stack({ activeCueId: 13, activeEntryId: 3 }) })
    expect(screen.getByText('→ release')).toBeTruthy()
  })

  it('says *no cues* for an empty stack, and offers no GO', () => {
    draw(stackPad, { stack: stack({ cues: [] }), onStackBack: () => {}, onStackRelease: () => {} })
    expect(screen.getByText('no cues')).toBeTruthy()
    hold(screen.getByRole('button'))
    expect(screen.getAllByRole('menuitem').map((i) => i.textContent)).toEqual(['Assign to surface…', 'View'])
  })

  it('offers GO and View while stopped, and Back and Release besides while running — never Pick up', () => {
    draw(stackPad, { stack: stack(), onStackBack: () => {}, onStackRelease: () => {} })
    hold(screen.getByRole('button'))
    expect(screen.getAllByRole('menuitem').map((i) => i.textContent)).toEqual(['GO', 'Assign to surface…', 'View'])
    cleanup()

    const onPress = vi.fn()
    const onStackBack = vi.fn()
    const onStackRelease = vi.fn()
    draw(stackPad, { stack: stack({ activeCueId: 12, activeEntryId: 2 }), onPress, onStackBack, onStackRelease })
    hold(screen.getByRole('button'))
    expect(screen.getAllByRole('menuitem').map((i) => i.textContent)).toEqual(['GO', 'Back', 'Release', 'Assign to surface…', 'View'])
    fireEvent.click(screen.getByText('Back'))
    expect(onStackBack).toHaveBeenCalledTimes(1)
    cleanup()

    draw(stackPad, { stack: stack({ activeCueId: 12, activeEntryId: 2 }), onPress, onStackBack, onStackRelease })
    hold(screen.getByRole('button'))
    fireEvent.click(screen.getByText('Release'))
    expect(onStackRelease).toHaveBeenCalledTimes(1)
    expect(onPress).not.toHaveBeenCalled()
  })

  it('offers each of GO and Back only where it does something (D9)', () => {
    // A Stay stack on its last cue: a tap does nothing, so no GO; Back still steps it.
    draw(stackPad, { stack: stack({ releaseAtEnd: false, activeCueId: 13, activeEntryId: 3 }), onStackBack: () => {}, onStackRelease: () => {} })
    hold(screen.getByRole('button'))
    expect(screen.getAllByRole('menuitem').map((i) => i.textContent)).toEqual(['Back', 'Release', 'Assign to surface…', 'View'])
    cleanup()

    // The first cue of a stack that does not loop: nothing behind it.
    draw(stackPad, { stack: stack({ activeCueId: 11, activeEntryId: 1 }), onStackBack: () => {}, onStackRelease: () => {} })
    hold(screen.getByRole('button'))
    expect(screen.getAllByRole('menuitem').map((i) => i.textContent)).toEqual(['GO', 'Release', 'Assign to surface…', 'View'])
  })

  it('names the stack as the list holds it, so a rename by any door shows', () => {
    draw(stackPad, { stack: stack({ name: 'Encore' }) })
    expect(screen.getByText('Encore')).toBeTruthy()
    expect(screen.queryByText('Song 3')).toBeNull()
  })

  it('draws a fade only for a GO heard since it mounted, never replaying an old one', () => {
    const frame = (transition: boolean, activeCueId: number, activeEntryId: number) =>
      applyServerRunState({
        projectId: 1, stackId: 9, activeCueId, activeEntryId, nextCueId: null, nextEntryId: null,
        standbyEntryId: null, nextIsArmed: false, transition, fadeDurationMs: 4000, fadeElapsedMs: 0,
        autoAdvance: false, autoAdvanceDelayMs: null,
      })
    // A GO long before the pad mounted: the slice has moved on from it.
    act(() => void store.dispatch(frame(true, 12, 2)))
    const slow = (overrides: Partial<CueStack>) =>
      stack({ ...overrides, cues: [row(1, 11, '1', 'Bow'), { ...row(2, 12, '2', 'Chorus'), fadeDurationMs: 4000 } as CueStack['cues'][number]] })
    const { container } = draw(stackPad, { stack: slow({ activeCueId: 12, activeEntryId: 2 }) })
    expect(container.querySelector('.bg-green-500\\/20')).toBeNull()

    act(() => void store.dispatch(frame(true, 11, 1)))
    expect(container.querySelector('.bg-green-500\\/20')).toBeTruthy()
  })

  it('is GO on a tap, and ⌥ changes nothing', () => {
    const onPress = vi.fn()
    const onApplyValues = vi.fn()
    draw(stackPad, { stack: stack(), onPress, onApplyValues })
    const pad = screen.getByRole('button')
    fireEvent.pointerDown(pad, { altKey: true, clientX: 0, clientY: 0 })
    fireEvent.pointerUp(pad)
    expect(onPress).toHaveBeenCalledTimes(1)
    expect(onApplyValues).not.toHaveBeenCalled()
  })
})
