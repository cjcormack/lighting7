// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode, useRef, useState, type ReactNode } from 'react'
import type { ActiveFx } from '@/store/fixtureFx'
import { chan, makeActiveFx, makeFixture, sliderProp } from '@/test/fixtureFactories'

/**
 * The split editor (apply-model plan §4.5, D5): with part of a Local effect selected the editor says
 * *Hex 1–2 of Hex 1–4*, holds the edit, and *Apply to Hex 1–2* sends it once over REST with the heads
 * as `targets`, then follows the edited copy; a palette layer's effect offers no split (P5).
 */
const updateFx = vi.hoisted(() => vi.fn((_id: number, _req: unknown) => true))
vi.mock('@/api/lightingApi', () => ({ lightingApi: { fx: { updateFx } } }))

const HEX = [1, 2, 3, 4].map((n) =>
  makeFixture(`hex-${n}`, [sliderProp('dimmer', 'dimmer', chan(n))], { name: `Hex ${n}`, groups: ['front'] }),
)
vi.mock('@/hooks/useFixtureLookup', () => ({ useFixtureLookup: () => ({ fixtures: HEX, fixtureByKey: new Map() }) }))
vi.mock('@/hooks/usePress', () => ({ useWindowPressPair: () => ({ targets: [], families: null }) }))
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => true }))

const COPY_1 = makeActiveFx({ id: 31, targetKey: 'hex-1' })
const COPY_2 = makeActiveFx({ id: 32, targetKey: 'hex-2' })
const splitFx = vi.hoisted(() =>
  vi.fn((_req: { id: number; update: Record<string, unknown> }) => ({
    unwrap: () => Promise.resolve({ splitFxId: 7, edited: [COPY_1, COPY_2], rest: [] }),
  })),
)
vi.mock('@/store/fixtureFx', () => ({
  useSplitFx: () => ({
    splitFx: (id: number, update: Record<string, unknown>) => splitFx({ id, update }).unwrap(),
    splitting: false,
  }),
  isFxSplit: (answer: object) => 'splitFxId' in answer,
}))
const stopFx = vi.hoisted(() => vi.fn())
vi.mock('./useFxControls', () => ({ useStopFx: () => stopFx }))
vi.mock('../editor/EditorSurface', () => ({
  EditorSurface: ({ open, children, onOpenChange }: { open: boolean; children: ReactNode; onOpenChange: (open: boolean) => void }) =>
    open ? (
      <div>
        {children}
        <button type="button" onClick={() => onOpenChange(false)}>
          dismiss
        </button>
      </div>
    ) : null,
}))
/** The editor stand-in: one move, held where the split editor holds it, else sent to every copy. */
vi.mock('./FxEditor', () => ({
  FxEditor: ({
    effect,
    hold,
    alsoIds,
    onStop,
  }: {
    effect: ActiveFx
    hold?: (request: unknown) => void
    alsoIds?: readonly number[]
    onStop?: () => void
  }) => (
    <>
      <button
        type="button"
        data-editing={effect.id}
        onClick={() => {
          const request = { parameters: { max: '200' } }
          if (hold != null) hold(request)
          else for (const id of [effect.id, ...(alsoIds ?? [])]) updateFx(id, request)
        }}
      >
        move
      </button>
      <button type="button" onClick={onStop}>
        stop
      </button>
    </>
  ),
}))

const { FxEditorPopover } = await import('./FxEditorPopover')

const FRONT = makeActiveFx({ id: 7, targetKey: 'front', isGroupTarget: true, targetType: 'group' })
const SUBSET = [
  { type: 'fixture' as const, key: 'hex-1' },
  { type: 'fixture' as const, key: 'hex-2' },
]

/** A host as the rail is: it reads the effect it edits from the running list by id. */
const hostStop = vi.fn()

function Host({ running, selection }: { running: ActiveFx[]; selection: typeof SUBSET }) {
  const [editingId, setEditingId] = useState<number | null>(running[0].id)
  const anchor = useRef<HTMLElement | null>(null)
  return (
    <>
      <FxEditorPopover
        effect={running.find((e) => e.id === editingId) ?? null}
        anchorRef={anchor}
        selection={selection}
        onClose={() => setEditingId(null)}
        onFollow={setEditingId}
        onStop={(effect) => {
          hostStop(effect.id)
          setEditingId(null)
        }}
      />
      <button type="button" onClick={() => setEditingId(null)}>
        close
      </button>
      <button type="button" onClick={() => setEditingId(31)}>
        open 31
      </button>
    </>
  )
}

beforeEach(() => {
  updateFx.mockClear()
  splitFx.mockClear()
  stopFx.mockClear()
  hostStop.mockClear()
})
afterEach(cleanup)

describe('FxEditorPopover — the split editor', () => {
  it('holds a subset edit and sends it with targets on Apply, then follows the edited copies', async () => {
    const { rerender } = render(<Host running={[FRONT]} selection={SUBSET} />)
    expect(screen.getByText('Hex 1–2').closest('[data-fx-split]')?.textContent).toContain('of Hex 1–4')
    const apply = screen.getByRole('button', { name: 'Apply to Hex 1–2' }) as HTMLButtonElement
    expect(apply.disabled).toBe(true)

    fireEvent.click(screen.getByRole('button', { name: 'move' }))
    expect(updateFx).not.toHaveBeenCalled()
    fireEvent.click(apply)
    await waitFor(() => expect(splitFx).toHaveBeenCalledTimes(1))
    expect(splitFx.mock.calls[0][0]).toEqual({ id: 7, update: { parameters: { max: '200' }, targets: SUBSET } })

    // The desk now runs the copies; the host reads the edited one.
    rerender(<Host running={[FRONT, COPY_1, COPY_2]} selection={SUBSET} />)
    await waitFor(() => expect(document.querySelector('[data-editing="31"]')).toBeTruthy())
    expect(document.querySelector('[data-fx-split]')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'move' }))
    expect(updateFx.mock.calls.map((c) => c[0])).toEqual([31, 32])
  })

  it('follows the edited copies under StrictMode too, whose doubled effects must not leave the editor thinking it is gone', async () => {
    const { rerender } = render(
      <StrictMode>
        <Host running={[FRONT]} selection={SUBSET} />
      </StrictMode>,
    )
    fireEvent.click(screen.getByRole('button', { name: 'move' }))
    fireEvent.click(screen.getByRole('button', { name: 'Apply to Hex 1–2' }))
    await waitFor(() => expect(splitFx).toHaveBeenCalledTimes(1))
    rerender(
      <StrictMode>
        <Host running={[FRONT, COPY_1, COPY_2]} selection={SUBSET} />
      </StrictMode>,
    )
    await waitFor(() => expect(document.querySelector('[data-editing="31"]')).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: 'move' }))
    expect(updateFx.mock.calls.map((c) => c[0])).toEqual([31, 32])
  })

  it('stops every copy it follows on ×, and a close ends the session', async () => {
    const { rerender } = render(<Host running={[FRONT]} selection={SUBSET} />)
    fireEvent.click(screen.getByRole('button', { name: 'move' }))
    fireEvent.click(screen.getByRole('button', { name: 'Apply to Hex 1–2' }))
    await waitFor(() => expect(splitFx).toHaveBeenCalledTimes(1))
    rerender(<Host running={[FRONT, COPY_1, COPY_2]} selection={SUBSET} />)
    await waitFor(() => expect(document.querySelector('[data-editing="31"]')).toBeTruthy())
    // A close, then copy 31 opened again: it is edited alone, no longer following copy 32.
    fireEvent.click(screen.getByRole('button', { name: 'dismiss' }))
    fireEvent.click(screen.getByRole('button', { name: 'open 31' }))
    fireEvent.click(screen.getByRole('button', { name: 'move' }))
    expect(updateFx.mock.calls.map((c) => c[0])).toEqual([31])
    fireEvent.click(screen.getByRole('button', { name: 'stop' }))
    expect(hostStop).toHaveBeenCalledWith(31)
    expect(stopFx).not.toHaveBeenCalled()
  })

  it('× after a split stops the other copies too', async () => {
    const { rerender } = render(<Host running={[FRONT]} selection={SUBSET} />)
    fireEvent.click(screen.getByRole('button', { name: 'move' }))
    fireEvent.click(screen.getByRole('button', { name: 'Apply to Hex 1–2' }))
    await waitFor(() => expect(splitFx).toHaveBeenCalledTimes(1))
    rerender(<Host running={[FRONT, COPY_1, COPY_2]} selection={SUBSET} />)
    await waitFor(() => expect(document.querySelector('[data-editing="31"]')).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: 'stop' }))
    expect(stopFx).toHaveBeenCalledWith(expect.objectContaining({ id: 32 }))
    expect(hostStop).toHaveBeenCalledWith(31)
  })

  it('drops a held edit when the selection moves off the subset, rather than sending it to every head', () => {
    const { rerender } = render(<Host running={[FRONT]} selection={SUBSET} />)
    fireEvent.click(screen.getByRole('button', { name: 'move' }))
    rerender(<Host running={[FRONT]} selection={HEX.map((f) => ({ type: 'fixture' as const, key: f.key }))} />)
    expect(document.querySelector('[data-fx-split]')).toBeNull()
    expect(updateFx).not.toHaveBeenCalled()
  })

  it('stays closed when the split answers after a close', async () => {
    let answer: (value: unknown) => void = () => {}
    splitFx.mockImplementationOnce(() => ({ unwrap: () => new Promise((resolve) => (answer = resolve)) }) as never)
    render(<Host running={[FRONT, COPY_1, COPY_2]} selection={SUBSET} />)
    fireEvent.click(screen.getByRole('button', { name: 'move' }))
    fireEvent.click(screen.getByRole('button', { name: 'Apply to Hex 1–2' }))
    fireEvent.click(screen.getByRole('button', { name: 'close' }))
    answer({ splitFxId: 7, edited: [COPY_1, COPY_2], rest: [] })
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(document.querySelector('[data-editing]')).toBeNull()
  })

  it('edits all its heads in place, live, on Edit all 4', () => {
    render(<Host running={[FRONT]} selection={SUBSET} />)
    fireEvent.click(screen.getByRole('button', { name: 'move' }))
    fireEvent.click(screen.getByRole('button', { name: 'Edit all 4' }))
    expect(updateFx).toHaveBeenCalledWith(7, { parameters: { max: '200' } })
    expect(document.querySelector('[data-fx-split]')).toBeNull()
    expect(splitFx).not.toHaveBeenCalled()
  })

  it('offers no split for a palette layer’s effect: it is edited as its layer’s', () => {
    const layer = { ...FRONT, paletteId: 4, programmerLayerId: 2, sourceName: 'Amber Breathe' }
    render(<Host running={[layer]} selection={SUBSET} />)
    expect(document.querySelector('[data-fx-split]')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'move' }))
    expect(updateFx).toHaveBeenCalledWith(7, { parameters: { max: '200' } })
  })

  it('edits in place with every head selected', () => {
    render(<Host running={[FRONT]} selection={HEX.map((f) => ({ type: 'fixture' as const, key: f.key }))} />)
    expect(document.querySelector('[data-fx-split]')).toBeNull()
  })
})
