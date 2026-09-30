// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, waitFor } from '@testing-library/react'
import { Provider } from 'react-redux'
import { installRecordingFetch, installRelativeUrlRequest } from '@/test/backendMock'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

// The real view draws WebGL; this one records the props the render hands it.
const drawn: Stage3DProps[] = []
vi.mock('../Stage3D', () => ({
  Stage3D: (props: Stage3DProps) => {
    drawn.push(props)
    return null
  },
}))

import type { ComponentProps } from 'react'
import { store } from '../../../store'
import { restApi } from '../../../store/restApi'
import type { Stage3D } from '../Stage3D'
import type { StageCaptureHandle } from '../CaptureCanvas'
import type { StageRenderRequest } from '../../../api/stageRenderApi'
import { DEFAULT_SCENE_LAYERS } from '../scene/sceneView'
import { STAGE_LANDED_KEY, STAGE_VIEWPOINT_KEY } from '../../../lib/stageViewpoint'
import StageRenderJob, { type StageRenderOutcome } from './StageRenderJob'

type Stage3DProps = ComponentProps<typeof Stage3D>

/**
 * One render (stage-view plan session 4), with the view itself stubbed: it waits for every read,
 * hands `Stage3D` the camera and landing its viewpoint resolves to — at the asked size, the default
 * layers, no labels — draws once the scene reports in, and gives up naming what never came.
 */

const SEATING = '0a1b2c3d-0000-4000-8000-00000000000a'
const DESK = '5b1f7a52-9c3e-4d8a-8f2e-1a2b3c4d5e6f'

const stalls = {
  id: 1, uuid: SEATING, name: 'Stalls', kind: 'SEATING', layer: 'VENUE',
  positionX: 0, positionY: -2.4, positionZ: -0.95, yawDeg: 0,
  widthM: 0, depthM: 0, heightM: 0, finishColour: null, finishPattern: null, emissive: false,
  params: { rows: 12, seatsPerRow: 12, rowPitchM: 0.95, seatPitchM: 0.52 }, hidden: false, sortOrder: 0,
}
const desk = {
  id: 1, uuid: DESK, name: 'Desk', kind: 'EYE',
  eyeX: 1.25, eyeY: -17, eyeZ: 2.6, targetX: 0, targetY: 2.6, targetZ: 0.8,
  fovDeg: 50, seatElementUuid: null, seatId: null, sortOrder: 0,
}

// Most specific first: a route is matched by URL substring.
const routes = {
  'projects/15/stage-viewpoints': [desk],
  'projects/15/stage-elements': [stalls],
  'projects/15/patches': [],
  'projects/15/stage-regions': [],
  'projects/15/riggings': [],
  'projects/15': { id: 15, name: 'Hall', stageWidthM: 8, stageDepthM: 6, stageHeightM: 5 },
  'fixture-types': [],
  fixtures: [],
}

const request = (over: Partial<StageRenderRequest> = {}): StageRenderRequest => ({
  requestId: 'r-1',
  token: 't',
  projectId: 15,
  viewpoint: 'plan',
  width: 640,
  height: 360,
  source: 'output',
  timeoutMs: 30_000,
  ...over,
})

const outcomes: StageRenderOutcome[] = []

function mount(r: StageRenderRequest) {
  return render(
    <Provider store={store}>
      <StageRenderJob request={r} onDone={(o) => outcomes.push(o)} />
    </Provider>,
  )
}

beforeEach(() => {
  drawn.length = 0
  outcomes.length = 0
  installRelativeUrlRequest()
  installRecordingFetch(routes)
  sessionStorage.clear()
})

afterEach(() => {
  vi.useRealTimers()
  store.dispatch(restApi.util.resetApiState())
  vi.unstubAllGlobals()
})

const last = () => drawn[drawn.length - 1]

describe('StageRenderJob', () => {
  it('draws the Stage view offscreen as a fresh window shows it, at the asked size', async () => {
    const { container } = mount(request())
    await waitFor(() => expect(drawn.length).toBeGreaterThan(0))
    const props = last()
    expect(props.camera).toBe('plan')
    expect(props.landing).toBeNull()
    expect(props.showScene).toBe(true)
    expect(props.editMode).toBe(false)
    expect(props.layers).toEqual(DEFAULT_SCENE_LAYERS)
    expect(props.view?.labels).toBe('none')
    expect(props.persistCamera).toBeFalsy()
    expect(props.capture).toMatchObject({ width: 640, height: 360 })
    // Offscreen, and out of the way of every pointer and screen reader.
    const box = container.querySelector<HTMLElement>('[data-stage-render="r-1"]')!
    expect(box.getAttribute('aria-hidden')).toBe('true')
    expect(box.style.left).toBe('-100000px')
    expect(box.style.pointerEvents).toBe('none')
  })

  it('lands a saved view and a seat through the rig the Stage view would', async () => {
    mount(request({ viewpoint: DESK }))
    await waitFor(() => expect(drawn.length).toBeGreaterThan(0))
    expect(last().camera).toBe('eye')
    expect(last().landing).toMatchObject({ ref: DESK, camera: 'eye' })

    drawn.length = 0
    mount(request({ requestId: 'r-2', viewpoint: `seat:${SEATING}:F6` }))
    await waitFor(() => expect(drawn.length).toBeGreaterThan(0))
    expect(last().camera).toBe('eye')
    expect(last().landing).toMatchObject({ ref: `seat:${SEATING}:F6`, camera: 'eye' })
  })

  it('draws, reads the frame and hands back the PNG once the scene reports in', async () => {
    mount(request())
    await waitFor(() => expect(drawn.length).toBeGreaterThan(0))
    const png = new Blob(['png'], { type: 'image/png' })
    const draw = vi.fn(async () => {})
    const handle: StageCaptureHandle = { draw, toPng: async () => png }
    act(() => last().capture!.onReady(handle))
    await waitFor(() => expect(outcomes).toEqual([{ png }]))
    expect(draw).toHaveBeenCalledWith(expect.any(Number))
  })

  it('reports a lost context or a failed renderer as the reason, once', async () => {
    mount(request())
    await waitFor(() => expect(drawn.length).toBeGreaterThan(0))
    act(() => {
      last().capture!.onError('the graphics context was lost')
      last().capture!.onError('and again')
    })
    expect(outcomes).toEqual([{ reason: 'the graphics context was lost' }])
  })

  it('refuses a viewpoint the project no longer has, without drawing', async () => {
    mount(request({ viewpoint: '7c9e6679-7425-40de-944b-e07fc1f90ae8' }))
    await waitFor(() => expect(outcomes).toHaveLength(1))
    expect((outcomes[0] as { reason: string }).reason).toMatch(/no longer resolves/)
    expect(drawn).toEqual([])
  })

  it('gives up before the desk does, naming what it was waiting for', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    mount(request({ timeoutMs: 5_000 }))
    await waitFor(() => expect(drawn.length).toBeGreaterThan(0))
    await act(async () => {
      vi.advanceTimersByTime(2_100)
    })
    expect(outcomes).toEqual([{ reason: 'it gave up after 2 s, waiting for the scene to mount' }])
  })

  it('keeps one deadline and one draw however often its host re-renders it', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const first: StageRenderOutcome[] = []
    const r = request({ timeoutMs: 5_000 })
    const view = render(
      <Provider store={store}>
        <StageRenderJob request={r} onDone={(o) => first.push(o)} />
      </Provider>,
    )
    await waitFor(() => expect(drawn.length).toBeGreaterThan(0))
    const capture = last().capture
    // The host hands a fresh callback each time it renders — here, three times over 1.5 s.
    for (let i = 0; i < 3; i++) {
      await act(async () => {
        vi.advanceTimersByTime(500)
      })
      view.rerender(
        <Provider store={store}>
          <StageRenderJob request={r} onDone={(o) => first.push(o)} />
        </Provider>,
      )
    }
    expect(last().capture).toBe(capture)
    await act(async () => {
      vi.advanceTimersByTime(600)
    })
    // Still 2 s from the first mount, not from the last re-render.
    expect(first).toEqual([{ reason: 'it gave up after 2 s, waiting for the scene to mount' }])
  })

  it('leaves this window’s own viewpoint and landed marker alone', async () => {
    sessionStorage.setItem(STAGE_VIEWPOINT_KEY, JSON.stringify('front'))
    mount(request({ viewpoint: DESK }))
    await waitFor(() => expect(drawn.length).toBeGreaterThan(0))
    expect(sessionStorage.getItem(STAGE_VIEWPOINT_KEY)).toBe(JSON.stringify('front'))
    expect(sessionStorage.getItem(STAGE_LANDED_KEY)).toBeNull()
  })
})
