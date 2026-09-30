import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useThree } from '@react-three/fiber'
import { OrbitControls } from '@react-three/drei'
import { OrthographicCamera, PerspectiveCamera, Vector3 } from 'three'
import type { OrbitControls as OrbitControlsImpl } from 'three-stdlib'
import {
  markViewpointLanded,
  useLandedViewpoint,
  type LandingRef,
  type OrthoCamera,
  type StageCamera,
} from '../../lib/stageViewpoint'
import {
  noteEyePose,
  noteOrbitPose,
  readEyePose,
  readOrbitPose,
  writeEyePose,
  writeOrbitPose,
  type EyePose,
  type OrbitPose,
  type Vec3,
} from '../../lib/stageCameraPoses'
import {
  boundingSphere,
  clampFov,
  clampPitch,
  eyeFromOrbit,
  eyeTarget,
  fitZoom,
  framedOrbitPose,
  framedOrthoPosition,
  lookAngles,
  orthoSection,
  type LightingBounds,
} from './stageCameras'

/**
 * What a drag handle, the fixture gizmo or a region handle switches off while it holds the pointer —
 * the orbit or ortho `OrbitControls`, or the eye's look-around. Only `enabled` is shared.
 */
export interface StageCameraControls {
  enabled: boolean
}

/** The route's handle on the current camera: *Frame the selection* (F). */
export interface StageCameraHandle {
  /** Bring these points (three.js space) into view, in whatever way the current camera moves. */
  frame(points: readonly Vec3[]): void
}

/**
 * A saved view or a picked seat for the rig to land on (stage-view plan sessions 2 and 3): its
 * reference and the pose its camera takes, in three.js space. Resolved by the Stage view from the
 * rows and the seating (`savedViewpoints.ts`); the rig decides *whether* to land — once per pick,
 * never on a remount that already has (`lib/stageViewpoint.ts`'s landed marker).
 */
export type StageCameraLanding =
  | { ref: LandingRef; camera: 'orbit'; pose: OrbitPose }
  | { ref: LandingRef; camera: 'eye'; pose: EyePose }

interface StageCameraRigProps {
  camera: StageCamera
  /** A saved view to land on, when the viewpoint is one and its rows have resolved it. */
  landing?: StageCameraLanding | null
  /** Where the orbit camera starts when nothing is stored — and what an eye with no pose seeds from. */
  defaultOrbit: OrbitPose
  /** The rig, for the orthographic sections' planes and their fit. */
  bounds: LightingBounds
  /**
   * The modelled venue and set, for how deep a section sees — never where its plane stands, which
   * is the rig's, so a section cuts the room (`stageCameras.ts`'s `orthoSection`).
   */
  beyond?: LightingBounds | null
  /**
   * Read and write this window's poses in `sessionStorage` (`lib/stageCameraPoses.ts`). The Stage
   * route's canvas does; the Positions panel's embedded plan does not — it is a second camera on
   * the same scene and must not move the Stage view's.
   */
  persist: boolean
  controlsRef: React.RefObject<StageCameraControls | null>
  handleRef?: React.RefObject<StageCameraHandle | null>
}

/**
 * The Stage view's cameras (stage-view plan session 1): one component per camera kind, so switching
 * viewpoint unmounts one camera and its controls and mounts the next, and nothing is shared that
 * could leak a pose between them.
 *
 * Each camera is a plain three.js object made the canvas's default through the store rather than a
 * drei `<PerspectiveCamera makeDefault>`: drei's orthographic camera allocates a render target for
 * a feature this never uses, and R3F sizes a default camera only on the *next* resize, so a camera
 * swapped in must be fitted to the canvas here or it renders with a unit frustum.
 *
 * Everything here writes the camera imperatively, so every move asks for a frame — the canvas
 * renders on demand (`stage-vis-engineering.md` §"The frameloop renders on demand"). drei's
 * `OrbitControls` invalidates on its own `change`; the eye and the fits call `invalidate` themselves.
 */
export function StageCameraRig(props: StageCameraRigProps) {
  const { camera } = props
  if (camera === 'orbit') return <OrbitRig {...props} />
  if (camera === 'eye') return <EyeRig {...props} />
  return <OrthoRig key={camera} {...props} view={camera} />
}

/**
 * The landing this rig should take now: one of its own camera's, on the Stage route's canvas (the
 * Positions plan never lands), and not the one the window has already landed on.
 */
function usePendingLanding<C extends 'orbit' | 'eye'>(
  camera: C,
  landing: StageCameraLanding | null | undefined,
  persist: boolean,
): Extract<StageCameraLanding, { camera: C }> | null {
  const landed = useLandedViewpoint()
  if (!persist || landing == null || landing.camera !== camera) return null
  if (landed?.ref === landing.ref && landed.camera === landing.camera) return null
  return landing as Extract<StageCameraLanding, { camera: C }>
}

const ORBIT_FOV_DEG = 45
const NEAR_M = 0.05
const FAR_M = 500
/** How long after a camera move its pose is written — a drag moves it every frame. */
const SAVE_DELAY_MS = 150

function toVec3(v: Vector3): Vec3 {
  return [v.x, v.y, v.z]
}

/**
 * Make [camera] the canvas's default for as long as the calling component is mounted, sized to the
 * canvas first; the previous default comes back on unmount.
 */
function useDefaultCamera(camera: PerspectiveCamera | OrthographicCamera): void {
  const get = useThree((s) => s.get)
  const set = useThree((s) => s.set)
  const invalidate = useThree((s) => s.invalidate)
  useLayoutEffect(() => {
    const { camera: previous, size } = get()
    if (camera instanceof OrthographicCamera) {
      camera.left = size.width / -2
      camera.right = size.width / 2
      camera.top = size.height / 2
      camera.bottom = size.height / -2
    } else if (size.width > 0 && size.height > 0) {
      camera.aspect = size.width / size.height
    }
    camera.updateProjectionMatrix()
    set({ camera })
    invalidate()
    return () => {
      if (get().camera === camera) set({ camera: previous })
    }
  }, [camera, get, set, invalidate])
}

/**
 * A pose writer that runs at most once per [SAVE_DELAY_MS] while the camera moves, and once more on
 * unmount if a write was pending — so a remount (a route change, *Restore*) finds the pose the
 * camera was left in, not one from a moment before.
 */
function useDeferredSave(enabled: boolean, write: () => void): () => void {
  const writeRef = useRef(write)
  writeRef.current = write
  const timer = useRef<number | null>(null)
  useEffect(
    () => () => {
      if (timer.current == null) return
      window.clearTimeout(timer.current)
      timer.current = null
      writeRef.current()
    },
    [],
  )
  return useCallback(() => {
    if (!enabled || timer.current != null) return
    timer.current = window.setTimeout(() => {
      timer.current = null
      writeRef.current()
    }, SAVE_DELAY_MS)
  }, [enabled])
}

/** Hand [value] to [ref] while mounted, and take it back only if nothing has replaced it since. */
function useLend<T>(ref: React.RefObject<T | null> | undefined, value: T | null): void {
  useEffect(() => {
    if (ref == null || value == null) return
    ref.current = value
    return () => {
      if (ref.current === value) ref.current = null
    }
  }, [ref, value])
}

// — orbit ————————————————————————————————————————————————————————————————

function OrbitRig({ defaultOrbit, persist, controlsRef, handleRef, landing }: StageCameraRigProps) {
  const pending = usePendingLanding('orbit', landing, persist)
  const [initial] = useState(() => pending?.pose ?? (persist ? readOrbitPose() : null) ?? defaultOrbit)
  const [target] = useState(() => [...initial.target] as [number, number, number])
  const [camera] = useState(() => {
    const c = new PerspectiveCamera(ORBIT_FOV_DEG, 1, NEAR_M, FAR_M)
    c.position.set(...initial.position)
    return c
  })
  useDefaultCamera(camera)
  const invalidate = useThree((s) => s.invalidate)
  const [controls, setControls] = useState<OrbitControlsImpl | null>(null)
  useLend(controlsRef, controls)

  const save = useDeferredSave(persist, () => {
    if (controls) writeOrbitPose({ position: toVec3(camera.position), target: toVec3(controls.target) })
  })
  // Every move is noted in memory at once, storage only as the save allows: an eye seeded in the
  // render that unmounts this rig must see where the camera is, not where it was 150 ms ago.
  const onChange = useCallback(() => {
    if (persist && controls) {
      noteOrbitPose({ position: toVec3(camera.position), target: toVec3(controls.target) })
    }
    save()
  }, [persist, controls, camera, save])

  const handle = useMemo<StageCameraHandle | null>(() => {
    if (controls == null) return null
    return {
      frame(points) {
        const sphere = boundingSphere(points)
        if (sphere == null) return
        const pose = framedOrbitPose(
          { position: toVec3(camera.position), target: toVec3(controls.target) },
          sphere.centre,
          sphere.radius,
          camera.fov,
          camera.aspect,
        )
        camera.position.set(...pose.position)
        controls.target.set(...pose.target)
        controls.update()
        invalidate()
        onChange()
      },
    }
  }, [camera, controls, invalidate, onChange])
  useLend(handleRef, handle)

  // Land a saved orbit view: on mount the initial pose already is it, so this only marks it; while
  // mounted (another saved orbit view, or the same one picked again) it moves the camera there.
  useEffect(() => {
    if (pending == null || controls == null) return
    camera.position.set(...pending.pose.position)
    controls.target.set(...pending.pose.target)
    controls.update()
    invalidate()
    onChange()
    markViewpointLanded({ ref: pending.ref, camera: 'orbit' })
  }, [pending, controls, camera, invalidate, onChange])

  return <OrbitControls ref={setControls} camera={camera} makeDefault target={target} onChange={onChange} />
}

// — eye ——————————————————————————————————————————————————————————————————

/** Radians of head turn per pixel of drag at a 50° lens; a narrower lens turns slower. */
const LOOK_RAD_PER_PX = 0.004

function EyeRig({ defaultOrbit, persist, controlsRef, handleRef, landing }: StageCameraRigProps) {
  const pending = usePendingLanding('eye', landing, persist)
  const [initial] = useState(
    () =>
      pending?.pose ??
      (persist ? readEyePose() : null) ??
      eyeFromOrbit((persist ? readOrbitPose() : null) ?? defaultOrbit),
  )
  const pose = useRef({ yaw: initial.yaw, pitch: initial.pitch, fov: initial.fov })
  const [camera] = useState(() => {
    const c = new PerspectiveCamera(initial.fov, 1, NEAR_M, FAR_M)
    c.position.set(...initial.position)
    return c
  })
  useDefaultCamera(camera)
  const invalidate = useThree((s) => s.invalidate)
  const gl = useThree((s) => s.gl)
  const connected = useThree((s) => s.events.connected) as HTMLElement | undefined
  const [controls] = useState<StageCameraControls>(() => ({ enabled: true }))
  useLend(controlsRef, controls)

  const save = useDeferredSave(persist, () =>
    writeEyePose({ position: toVec3(camera.position), ...pose.current }),
  )

  const apply = useCallback(() => {
    const { yaw, pitch, fov } = pose.current
    if (camera.fov !== fov) {
      camera.fov = fov
      camera.updateProjectionMatrix()
    }
    camera.lookAt(...eyeTarget({ position: toVec3(camera.position), yaw, pitch }))
    if (persist) noteEyePose({ position: toVec3(camera.position), yaw, pitch, fov })
    invalidate()
    save()
  }, [camera, invalidate, save, persist])

  useLayoutEffect(() => apply(), [apply])

  // Land a saved eye or seat view: stand where it stands and look where it looks. On mount the
  // initial pose already is it; while mounted it moves the eye — a seat picked after a standing
  // view, or the same seat picked again after looking round.
  useEffect(() => {
    if (pending == null) return
    camera.position.set(...pending.pose.position)
    pose.current = { yaw: pending.pose.yaw, pitch: pending.pose.pitch, fov: pending.pose.fov }
    apply()
    markViewpointLanded({ ref: pending.ref, camera: 'eye' })
  }, [pending, camera, apply])

  // Drag turns the head, grabbing the scene: drag right and the view turns left, as a panorama
  // does. Scroll (and a trackpad pinch, which arrives as a ctrl-wheel) narrows or widens the lens —
  // a person does not walk forward by scrolling. Listening where drei's controls listen, and on the
  // window for the rest of a drag, so a drag that leaves the canvas still ends.
  useEffect(() => {
    const el = connected ?? gl.domElement
    const previousTouchAction = el.style.touchAction
    el.style.touchAction = 'none'
    let drag: { x: number; y: number } | null = null
    const onDown = (e: PointerEvent) => {
      if (e.button !== 0 || !controls.enabled) return
      drag = { x: e.clientX, y: e.clientY }
    }
    const onMove = (e: PointerEvent) => {
      if (drag == null) return
      if (!controls.enabled) {
        drag = null
        return
      }
      const scale = (LOOK_RAD_PER_PX * pose.current.fov) / 50
      pose.current.yaw -= (e.clientX - drag.x) * scale
      pose.current.pitch = clampPitch(pose.current.pitch + (e.clientY - drag.y) * scale)
      drag = { x: e.clientX, y: e.clientY }
      apply()
    }
    const onUp = () => {
      drag = null
    }
    const onWheel = (e: WheelEvent) => {
      if (!controls.enabled) return
      e.preventDefault()
      pose.current.fov = clampFov(pose.current.fov * Math.exp(e.deltaY * 0.001))
      apply()
    }
    el.addEventListener('pointerdown', onDown)
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    window.addEventListener('pointercancel', onUp)
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => {
      el.style.touchAction = previousTouchAction
      el.removeEventListener('pointerdown', onDown)
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      window.removeEventListener('pointercancel', onUp)
      el.removeEventListener('wheel', onWheel)
    }
  }, [connected, gl, controls, apply])

  // Framing from where you stand turns the head; the eye does not walk.
  const handle = useMemo<StageCameraHandle>(
    () => ({
      frame(points) {
        const sphere = boundingSphere(points)
        if (sphere == null) return
        const { yaw, pitch } = lookAngles(toVec3(camera.position), sphere.centre)
        pose.current.yaw = yaw
        pose.current.pitch = pitch
        apply()
      },
    }),
    [camera, apply],
  )
  useLend(handleRef, handle)
  return null
}

// — the orthographic sections ————————————————————————————————————————————————

/** A fit leaves this much of the canvas round the scene. */
const ORTHO_FIT_FILL = 0.94

function OrthoRig({
  view,
  bounds,
  beyond = null,
  controlsRef,
  handleRef,
}: StageCameraRigProps & { view: OrthoCamera }) {
  const section = useMemo(() => orthoSection(view, bounds, beyond), [view, bounds, beyond])
  const [camera] = useState(() => {
    const c = new OrthographicCamera(-1, 1, 1, -1, 0.01, section.far)
    // Before the controls are built: `OrbitControls` reads the camera's up once, at construction.
    c.up.set(...section.up)
    c.position.set(...section.position)
    return c
  })
  useDefaultCamera(camera)
  const invalidate = useThree((s) => s.invalidate)
  const size = useThree((s) => s.size)
  const [controls, setControls] = useState<OrbitControlsImpl | null>(null)
  useLend(controlsRef, controls)
  // Until the operator pans or zooms, the section keeps itself fitted — to the canvas as it
  // resizes, and to the rig as its lists arrive. After that it is theirs.
  const moved = useRef(false)

  useLayoutEffect(() => {
    if (controls == null || moved.current) return
    camera.position.set(...section.position)
    camera.far = section.far
    camera.zoom = fitZoom(section.width, section.height, size.width, size.height) * ORTHO_FIT_FILL
    camera.updateProjectionMatrix()
    controls.target.set(...section.target)
    controls.update()
    invalidate()
  }, [camera, controls, section, size.width, size.height, invalidate])

  // How deep the section sees follows the scene even after the operator has moved it: a pan or a
  // frame slides the camera within its plane, never along the view axis, so the far plane measured
  // from the section still holds — and a venue that loads late must not stay clipped.
  useLayoutEffect(() => {
    if (camera.far === section.far) return
    camera.far = section.far
    camera.updateProjectionMatrix()
    invalidate()
  }, [camera, section.far, invalidate])

  const handle = useMemo<StageCameraHandle | null>(() => {
    if (controls == null) return null
    return {
      frame(points) {
        const sphere = boundingSphere(points)
        if (sphere == null) return
        moved.current = true
        const next = framedOrthoPosition(toVec3(camera.position), toVec3(controls.target), sphere.centre)
        camera.position.set(...next.position)
        controls.target.set(...next.target)
        camera.zoom = fitZoom(sphere.radius * 2, sphere.radius * 2, size.width, size.height) * ORTHO_FIT_FILL
        camera.updateProjectionMatrix()
        controls.update()
        invalidate()
      },
    }
  }, [camera, controls, size, invalidate])
  useLend(handleRef, handle)

  return (
    <OrbitControls
      ref={setControls}
      camera={camera}
      makeDefault
      enableRotate={false}
      screenSpacePanning
      minZoom={1}
      maxZoom={4000}
      onStart={() => {
        moved.current = true
      }}
    />
  )
}
