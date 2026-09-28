import { BODY_LENS_COLOR, housingColor, yokeColor } from './palette'
import { bodyScale, type FixtureBodyProps } from './types'

const HOUSING_LEN = 0.14
const DESIGN_SIZE = 0.21

export function ParBody({
  active,
  headRef,
  lensRef,
  dims,
  emitAxis = -1,
}: FixtureBodyProps) {
  return (
    <group scale={bodyScale(dims, DESIGN_SIZE)}>
      <mesh position={[0, 0, 0.075]}>
        <boxGeometry args={[0.16, 0.03, 0.03]} />
        <meshStandardMaterial color={yokeColor(active)} />
      </mesh>
      <group ref={headRef}>
        {/* Untapered, so only the offsets flip with emitAxis. */}
        <mesh position={[0, (emitAxis * HOUSING_LEN) / 2, 0]}>
          <cylinderGeometry args={[0.075, 0.075, HOUSING_LEN, 18]} />
          <meshStandardMaterial color={housingColor(active)} />
        </mesh>
        <mesh ref={lensRef} position={[0, emitAxis * HOUSING_LEN, 0]}>
          <sphereGeometry args={[0.07, 16, 10]} />
          <meshBasicMaterial color={BODY_LENS_COLOR} />
        </mesh>
      </group>
    </group>
  )
}
