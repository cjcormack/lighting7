// @vitest-environment jsdom
import { render } from '@testing-library/react'
import { act } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { bandFor, useNarrowContainer } from './useContainerBand'

/**
 * `useContainerBand` and its two-band wrapper `useNarrowContainer`.
 *
 * jsdom implements neither `ResizeObserver` nor layout, so both are stubbed: `getBoundingClientRect`
 * feeds the synchronous first measure, and `fire()` stands in for an observer callback.
 *
 * That makes this suite the only automated check on the hook's *decisions*. The four cases below
 * are each a bug that shipped or nearly shipped: a narrow-first default that flashed the phone
 * layout on every desk mount, a first paint that waited for the observer, a zero-width report from
 * a hidden ancestor reading as "narrow", and a threshold prop that stayed inert until a resize.
 */
let fire: ((width: number) => void) | null = null

beforeEach(() => {
  fire = null
  vi.stubGlobal(
    'ResizeObserver',
    class {
      constructor(private cb: ResizeObserverCallback) {
        fire = (width: number) =>
          this.cb([{ contentRect: { width } } as ResizeObserverEntry], this as never)
      }
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/** Width the element reports to the *synchronous* measure. `null` mounts no element at all. */
function Probe({ threshold, width }: { threshold: number; width: number | null }) {
  const [ref, narrow] = useNarrowContainer(threshold)
  return (
    <div
      ref={(el) => {
        if (el && width != null) {
          el.getBoundingClientRect = () => ({ width }) as DOMRect
        }
        ref(width == null ? null : el)
      }}
      data-testid="probe"
      data-narrow={String(narrow)}
    />
  )
}

const readNarrow = (c: HTMLElement) => c.querySelector('[data-testid="probe"]')?.getAttribute('data-narrow')

describe('useNarrowContainer', () => {
  it('assumes wide before anything has been measured', () => {
    // The old default was `true`, so every mount painted one frame of the phone layout.
    const { container } = render(<Probe threshold={1000} width={null} />)
    expect(readNarrow(container)).toBe('false')
  })

  it('resolves a sub-threshold width synchronously, before any observer fire', () => {
    const { container } = render(<Probe threshold={1000} width={600} />)
    // `fire` has been created but never called: this answer came from the ref callback alone.
    expect(readNarrow(container)).toBe('true')
  })

  it('ignores a zero width and keeps the last value it believed', () => {
    // A display:none ancestor reports 0. Reading that as "narrow" flipped Run to RunMobile.
    const { container } = render(<Probe threshold={1000} width={1400} />)
    expect(readNarrow(container)).toBe('false')
    act(() => fire?.(0))
    expect(readNarrow(container)).toBe('false')
  })

  it('re-decides when the threshold changes, without waiting for a resize', () => {
    // `CueCardEditor` passes its threshold as a prop; the element does not resize when it moves.
    const { container, rerender } = render(<Probe threshold={500} width={800} />)
    expect(readNarrow(container)).toBe('false')
    rerender(<Probe threshold={1000} width={800} />)
    expect(readNarrow(container)).toBe('true')
  })
})

describe('bandFor', () => {
  const floors = [1072, 560, 304]

  it('takes the widest band the width reaches', () => {
    expect(bandFor(floors, 1400)).toBe(0)
    expect(bandFor(floors, 1072)).toBe(0)
    expect(bandFor(floors, 1071)).toBe(1)
    expect(bandFor(floors, 560)).toBe(1)
    expect(bandFor(floors, 559)).toBe(2)
  })

  it('falls back to the narrowest band rather than off the end', () => {
    expect(bandFor(floors, 100)).toBe(2)
    expect(bandFor(floors, 0)).toBe(2)
  })

  it('answers 0 for an empty set rather than -1', () => {
    expect(bandFor([], 500)).toBe(0)
  })
})
