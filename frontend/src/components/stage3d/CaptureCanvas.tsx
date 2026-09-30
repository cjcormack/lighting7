import { Component, Suspense, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { advance, createRoot, extend, type ReconcilerRoot, type RootState } from '@react-three/fiber'
import * as THREE from 'three'
import type { StoreApi, UseBoundStore } from 'zustand'
import { ChannelSourceProvider, useChannelSource } from '../../hooks/useChannelSource'

/**
 * What a render hands the Stage view instead of a visible canvas (`render_view`, stage-view plan
 * session 4): a size in pixels, and where to report.
 */
export interface StageCapture {
  width: number
  height: number
  /** The scene has mounted — every lazy asset resolved — and can be drawn and read through [handle]. */
  onReady(handle: StageCaptureHandle): void
  /** The capture cannot go on: its renderer could not start, or its context was lost. */
  onError(reason: string): void
}

export interface StageCaptureHandle {
  /**
   * Draw [frames] frames, a task apart so the effects between them land (the bloom composer's
   * rebuild after its camera is swapped in, a light table packed by the frame before).
   */
  draw(frames: number): Promise<void>
  /** The last frame drawn, as a PNG. */
  toPng(): Promise<Blob>
}

type Store = UseBoundStore<StoreApi<RootState>>

/**
 * The Stage view's canvas for a render: an R3F root on a **detached** `<canvas>` of a fixed size,
 * drawn only when [StageCaptureHandle.draw] says so. Three reasons it is not R3F's `<Canvas>`:
 *
 * - **Nothing on screen.** The canvas is never attached to the document, so the window's own
 *   canvas, layout and pointer are untouched; it is a second renderer and context, disposed — and
 *   its context lost on purpose — when this unmounts (R3F's `unmount`).
 * - **No measuring.** `<Canvas>` sizes itself from a `ResizeObserver`, which a hidden tab never
 *   delivers; this root is configured with its size, so a window in the background still renders.
 * - **No animation frame.** `frameloop: 'never'` and [advance] drive it, for the same hidden tab
 *   (no `requestAnimationFrame` there), and so a live show's moving channels cannot keep it busy.
 *
 * DPR is 1 — the size asked for is the picture's — and the drawing buffer is preserved so the frame
 * can be read after it is drawn. Everything else is the view's: `flat`, no tone mapping,
 * `antialias`, the same scene children.
 *
 * **Contexts do not cross into an R3F root by themselves.** `<Canvas>` bridges every one; this
 * bridges the one the scene reads from outside the canvas — the vis source
 * (`ChannelSourceContext`). Anything else the scene reads is provided inside `Stage3D`'s children
 * (invalidate, labels, surface lighting). A scene component that starts reading another outside
 * context must be bridged here too, or the render silently draws its default.
 */
export function CaptureCanvas({
  capture,
  background,
  children,
}: {
  capture: StageCapture
  /** What the on-screen canvas shows through its CSS background; a PNG needs it drawn. */
  background: string
  children: ReactNode
}) {
  const source = useChannelSource()
  const captureRef = useRef(capture)
  captureRef.current = capture
  const [root, setRoot] = useState<{ root: ReconcilerRoot<HTMLCanvasElement>; canvas: HTMLCanvasElement } | null>(null)
  // What the root has told us: its store (known once `render` returns) and whether the scene has
  // committed (which, for a scene that does not suspend, happens *inside* that same `render`).
  const progress = useRef<{ store: Store | null; mounted: boolean; reported: boolean }>({
    store: null,
    mounted: false,
    reported: false,
  })
  const { width, height } = capture

  // One root per mount, on a canvas of its own — so a StrictMode replay makes a fresh one rather
  // than configuring a root whose teardown is still in flight.
  useLayoutEffect(() => {
    // The THREE catalogue `<ambientLight>` and the rest resolve against. `<Canvas>` registers it
    // as it mounts and `createRoot` does not — so a window that has never drawn a canvas (one on
    // Busk, asked to render) would otherwise throw on the scene's first element.
    // (The namespace holds constants beside its classes, which the catalogue type does not allow
    // for; `<Canvas>` passes the same namespace.)
    extend(THREE as unknown as Parameters<typeof extend>[0])
    const canvas = document.createElement('canvas')
    const created = createRoot(canvas)
    progress.current = { store: null, mounted: false, reported: false }
    created
      .configure({
        gl: { toneMapping: THREE.NoToneMapping, antialias: true, preserveDrawingBuffer: true },
        flat: true,
        dpr: 1,
        frameloop: 'never',
        size: { width, height, top: 0, left: 0 },
        // The view's canvas is transparent over a CSS background; a PNG of that would be too.
        onCreated: (state) => state.gl.setClearColor(background, 1),
      })
      .catch((e: unknown) => captureRef.current.onError(`the renderer could not start: ${describe(e)}`))
    setRoot({ root: created, canvas })
    return () => {
      progress.current = { store: null, mounted: false, reported: false }
      setRoot(null)
      created.unmount()
    }
  }, [width, height, background])

  // The scene, re-rendered into the root on every render of this component, as `<Canvas>` does.
  useLayoutEffect(() => {
    if (root == null) return
    const report = () => {
      const p = progress.current
      if (p.reported || !p.mounted || p.store == null) return
      p.reported = true
      const store = p.store
      captureRef.current.onReady({
        draw: async (frames) => {
          for (let i = 0; i < frames; i++) {
            await nextTask()
            if (progress.current.store !== store) throw new Error('the render was taken down')
            advance(performance.now() / 1000, false, store.getState())
          }
        },
        toPng: () =>
          new Promise<Blob>((resolve, reject) => {
            root.canvas.toBlob(
              (blob) => (blob ? resolve(blob) : reject(new Error('the frame could not be read'))),
              'image/png',
            )
          }),
      })
    }
    const onMounted = () => {
      progress.current.mounted = true
      report()
    }
    progress.current.store = root.root.render(
      <ChannelSourceProvider source={source}>
        <SceneBoundary onError={(reason) => captureRef.current.onError(reason)}>
          <Suspense fallback={null}>
            {children}
            <Mounted onMounted={onMounted} />
          </Suspense>
        </SceneBoundary>
      </ChannelSourceProvider>,
    ) as Store
    report()
  })

  return null
}

/**
 * Commits only once everything beside it in the Suspense boundary has resolved — the stage text's
 * font, the scene's lazy assets — which is what "the scene has mounted" means.
 */
function Mounted({ onMounted }: { onMounted: () => void }) {
  const ref = useRef(onMounted)
  ref.current = onMounted
  useLayoutEffect(() => {
    ref.current()
  }, [])
  return null
}

/**
 * A scene that throws inside the root ends the render with its reason at once. Without it the
 * throw stays inside the R3F root — nothing above the detached canvas hears it — and the render
 * would wait out its timeout for a scene that will never mount.
 */
class SceneBoundary extends Component<{ onError: (reason: string) => void; children: ReactNode }, { failed: boolean }> {
  state = { failed: false }

  static getDerivedStateFromError() {
    return { failed: true }
  }

  componentDidCatch(error: unknown) {
    this.props.onError(`the scene threw: ${error instanceof Error ? error.message : String(error)}`)
  }

  render() {
    return this.state.failed ? null : this.props.children
  }
}

/**
 * Yield to the event loop. A message-channel post, not `setTimeout`: a background tab clamps timers
 * to a second or more, and the window asked to render may well be one.
 */
function nextTask(): Promise<void> {
  return new Promise((resolve) => {
    const channel = new MessageChannel()
    channel.port1.onmessage = () => {
      channel.port1.close()
      resolve()
    }
    channel.port2.postMessage(null)
  })
}

function describe(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}
