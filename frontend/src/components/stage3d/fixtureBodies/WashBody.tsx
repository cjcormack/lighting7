import { BODY_LENS_COLOR, housingColor, yokeColor } from './palette'
import { bodyScale, type FixtureBodyProps } from './types'

const HOUSING_LEN = 0.1
const DESIGN_SIZE = 0.2

export function WashBody({
  active,
  headRef,
  lensRef,
  dims,
  emitAxis = -1,
}: FixtureBodyProps) {
  return (
    <group scale={bodyScale(dims, DESIGN_SIZE)}>
      <mesh position={[0, 0, 0.08]}>
        <boxGeometry args={[0.2, 0.03, 0.03]} />
        <meshStandardMaterial color={yokeColor(active)} />
      </mesh>
      <group ref={headRef}>
        {/* args are [radiusTop, radiusBottom] — reversed when the lens moves to
            the +Y face. */}
        <mesh position={[0, (emitAxis * HOUSING_LEN) / 2, 0]}>
          <cylinderGeometry
            args={
              emitAxis > 0
                ? [0.095, 0.1, HOUSING_LEN, 20]
                : [0.1, 0.095, HOUSING_LEN, 20]
            }
          />
          <meshStandardMaterial color={housingColor(active)} />
        </mesh>
        <mesh ref={lensRef} position={[0, emitAxis * HOUSING_LEN, 0]}>
          <sphereGeometry args={[0.09, 16, 10]} />
          <meshBasicMaterial color={BODY_LENS_COLOR} />
        </mesh>
      </group>
    </group>
  )
}
