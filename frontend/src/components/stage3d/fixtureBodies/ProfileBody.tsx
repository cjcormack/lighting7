import { BODY_LENS_COLOR, housingColor, yokeColor } from './palette'
import { bodyScale, type FixtureBodyProps } from './types'

const HOUSING_LEN = 0.36
const DESIGN_SIZE = 0.4

// Long ellipsoidal-spot housing (Source 4-style). Usually a fixed fixture aimed
// entirely by patch.baseYawDeg/basePitchDeg on the parent group, in which case
// the head subgroup is an idle pass-through. But NOT always idle: the Source 4
// Revolution is a moving-yoke profile registered as FixtureKind.PROFILE with
// real PAN/TILT axes, so this body does receive a live quaternion. It has no
// modelled yoke, so it takes pan and tilt combined on headRef.
export function ProfileBody({
  active,
  headRef,
  lensRef,
  dims,
  emitAxis = -1,
}: FixtureBodyProps) {
  return (
    <group scale={bodyScale(dims, DESIGN_SIZE)}>
      <mesh position={[0, 0, 0.12]}>
        <boxGeometry args={[0.18, 0.04, 0.04]} />
        <meshStandardMaterial color={yokeColor(active)} />
      </mesh>
      <group ref={headRef}>
        {/* Slight taper toward the lens end reads as ellipsoidal; args are
            [radiusTop, radiusBottom], so the pair flips with emitAxis. */}
        <mesh position={[0, (emitAxis * HOUSING_LEN) / 2, 0]}>
          <cylinderGeometry
            args={
              emitAxis > 0
                ? [0.07, 0.045, HOUSING_LEN, 16]
                : [0.045, 0.07, HOUSING_LEN, 16]
            }
          />
          <meshStandardMaterial color={housingColor(active)} />
        </mesh>
        <mesh ref={lensRef} position={[0, emitAxis * HOUSING_LEN, 0]}>
          <sphereGeometry args={[0.045, 14, 10]} />
          <meshBasicMaterial color={BODY_LENS_COLOR} />
        </mesh>
      </group>
    </group>
  )
}
