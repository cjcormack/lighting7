import { createContext, useContext, useEffect, useRef } from 'react'
import { useFrame, useThree } from '@react-three/fiber'
import type { Group } from 'three'
import { StageLabelStore, type StageLabelEntry, type StageLabelKind } from './stageLabels'

/**
 * The label layer's store, provided inside the canvas by `Stage3D`. Null outside a Stage3D —
 * a `StageLabel` there draws nothing rather than throwing, so a scene component can be mounted
 * in a test canvas without the layer.
 */
export const StageLabelContext = createContext<StageLabelStore | null>(null)

interface StageLabelProps {
  /** Where the label sits, in the parent's local space — as the old drei `<Html>` took it. */
  position: [number, number, number]
  children: string
  kind: StageLabelKind
  /** Hovered or selected: shown under *Positions* too, and placed ahead of everything else. */
  emphasised?: boolean
}

/**
 * A label anchored in the scene. It renders one empty group — the anchor — and registers it with
 * the layer; the text is a `<div>` the layer owns, not a React root (see `stageLabels.ts`).
 * Whether it is shown is the layer's decision, so the call sites mount it unconditionally.
 */
export function StageLabel({ position, children, kind, emphasised = false }: StageLabelProps) {
  const store = useContext(StageLabelContext)
  const anchorRef = useRef<Group>(null)
  const entryRef = useRef<StageLabelEntry | null>(null)

  // Registration follows the store's life, not the text's: a rename or a hover must restyle the
  // one `<div>`, never tear it down and append a new one.
  useEffect(() => {
    if (!store) return
    const entry = store.add(kind, children, emphasised)
    entry.anchor = anchorRef.current
    entryRef.current = entry
    return () => {
      store.remove(entry)
      entryRef.current = null
    }
    // The text, kind and emphasis are applied by the effect below; listing them here would
    // re-create the element on every hover, which is the churn this component exists to avoid.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [store])

  useEffect(() => {
    const entry = entryRef.current
    if (store && entry) store.update(entry, kind, children, emphasised)
  }, [store, kind, children, emphasised])

  // No invalidate for a moved anchor: `position`, and the parent carrying it, are props R3F
  // applies, and an applied prop change already asks the `demand` frameloop for a frame.
  return <group ref={anchorRef} position={position} />
}

/**
 * Lays the labels out once per rendered frame, after the camera controls have moved the camera
 * (they run at priority -1) and before the frame is drawn. It also hands the store the canvas's
 * `invalidate`, so a label change on the `demand` frameloop asks for the frame that shows it.
 */
export function StageLabelDriver({ store, paused }: { store: StageLabelStore; paused: boolean }) {
  const invalidate = useThree((s) => s.invalidate)
  useEffect(() => {
    store.invalidate = invalidate
    invalidate()
    return () => {
      store.invalidate = () => {}
    }
  }, [store, invalidate])

  useEffect(() => {
    if (paused) store.hideAll()
    else invalidate()
  }, [store, paused, invalidate])

  useFrame(({ camera, size }) => {
    if (paused) return
    camera.updateMatrixWorld()
    store.layout(camera, size.width, size.height)
  })
  return null
}
