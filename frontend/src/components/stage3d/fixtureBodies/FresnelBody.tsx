import { BODY_LENS_COLOR, housingColor, yokeColor } from './palette'
import { bodyScale, type FixtureBodyProps } from './types'

const HOUSING_LEN = 0.2
const DESIGN_SIZE = 0.28

export function FresnelBody({
  active,
  headRef,
  lensRef,
  dims,
  emitAxis = -1,
}: FixtureBodyProps) {
  return (
    <group scale={bodyScale(dims, DESIGN_SIZE)}>
      <mesh position={[0, 0, 0.1]}>
        <boxGeometry args={[0.2, 0.04, 0.04]} />
        <meshStandardMaterial color={yokeColor(active)} />
      </mesh>
      <group ref={headRef}>
        {/* args are [radiusTop, radiusBottom] — reversed when the lens moves to
            the +Y face. */}
        <mesh position={[0, (emitAxis * HOUSING_LEN) / 2, 0]}>
          <cylinderGeometry
            args={
              emitAxis > 0
                ? [0.075, 0.085, HOUSING_LEN, 18]
                : [0.085, 0.075, HOUSING_LEN, 18]
            }
          />
          <meshStandardMaterial color={housingColor(active)} />
        </mesh>
        <mesh ref={lensRef} position={[0, emitAxis * HOUSING_LEN, 0]}>
          <sphereGeometry args={[0.08, 16, 10]} />
          <meshBasicMaterial color={BODY_LENS_COLOR} />
        </mesh>
      </group>
    </group>
  )
}
