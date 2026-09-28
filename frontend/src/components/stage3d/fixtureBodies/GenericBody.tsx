import { BODY_LENS_COLOR } from './palette'
import { bodyScale, type FixtureBodyProps } from './types'

const DESIGN_SIZE = 0.2

// Cylinder yoke + sphere lens — the dispatch fallback for unknown or unset
// kinds, and also what a SCANNER renders as, so this body does get a live
// pan/tilt quaternion (the Scantastic 4 declares both axes). It needs no
// `emitAxis` mirror: the lens is a sphere centred on the head origin, so it
// looks the same whichever face emits, and the beam direction is FixtureModel's
// business rather than the body's.
export function GenericBody({ active, headRef, lensRef, dims }: FixtureBodyProps) {
  return (
    <group scale={bodyScale(dims, DESIGN_SIZE)}>
      <mesh position={[0, -0.05, 0]}>
        <cylinderGeometry args={[0.08, 0.1, 0.1, 16]} />
        <meshStandardMaterial color={active ? '#9aa5b4' : '#6a7280'} />
      </mesh>
      <group ref={headRef}>
        <mesh ref={lensRef}>
          <sphereGeometry args={[0.07, 16, 12]} />
          <meshBasicMaterial color={BODY_LENS_COLOR} />
        </mesh>
      </group>
    </group>
  )
}
