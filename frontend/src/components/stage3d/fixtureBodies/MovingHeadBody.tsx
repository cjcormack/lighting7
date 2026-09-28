import { BODY_LENS_COLOR, housingColor, yokeColor } from './palette'
import { bodyScale, type FixtureBodyProps } from './types'

const ARM_HEIGHT = 0.2
const ARM_X = 0.085
// Between the arms rather than perched on their tips, so the head sweeps
// through the yoke the way the real trunnion does. Head radius (0.075) stays
// under ARM_X so the housing clears the arms across the full tilt range.
const HEAD_PIVOT_Y = 0.14
const HOUSING_LEN = 0.16
// Largest design extent (base-to-head vertical) — sized to the real fixture.
const DESIGN_SIZE = 0.33

// Base bolted to truss or floor; yoke pans about the body's up axis carrying
// both arms; head tilts between them. `emitAxis` is +1 here in practice (a
// moving head always has a tilt axis), which puts the lens on the +Y face so
// mid-DMX tilt aims straight up the body, away from the base.
export function MovingHeadBody({
  active,
  headRef,
  yokeRef,
  lensRef,
  dims,
  emitAxis = -1,
}: FixtureBodyProps) {
  const yoke = yokeColor(active)
  const housing = housingColor(active)
  return (
    <group scale={bodyScale(dims, DESIGN_SIZE)}>
      <mesh position={[0, -0.04, 0]}>
        <cylinderGeometry args={[0.1, 0.11, 0.06, 20]} />
        <meshStandardMaterial color={yoke} />
      </mesh>
      <group ref={yokeRef}>
        <mesh position={[ARM_X, ARM_HEIGHT / 2, 0]}>
          <boxGeometry args={[0.03, ARM_HEIGHT, 0.06]} />
          <meshStandardMaterial color={yoke} />
        </mesh>
        <mesh position={[-ARM_X, ARM_HEIGHT / 2, 0]}>
          <boxGeometry args={[0.03, ARM_HEIGHT, 0.06]} />
          <meshStandardMaterial color={yoke} />
        </mesh>
        <group ref={headRef} position={[0, HEAD_PIVOT_Y, 0]}>
          {/* args are [radiusTop, radiusBottom] — +Y end first. The housing is
              slightly narrower at the lens, so the pair flips with emitAxis. */}
          <mesh>
            <cylinderGeometry
              args={
                emitAxis > 0
                  ? [0.07, 0.075, HOUSING_LEN, 18]
                  : [0.075, 0.07, HOUSING_LEN, 18]
              }
            />
            <meshStandardMaterial color={housing} />
          </mesh>
          {/* Sphere lens (not a disc) avoids edge-on flicker through bloom
              during orbit. */}
          <mesh ref={lensRef} position={[0, (emitAxis * HOUSING_LEN) / 2, 0]}>
            <sphereGeometry args={[0.06, 16, 12]} />
            <meshBasicMaterial color={BODY_LENS_COLOR} />
          </mesh>
        </group>
      </group>
    </group>
  )
}
