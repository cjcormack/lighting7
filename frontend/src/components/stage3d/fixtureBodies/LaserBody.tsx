import { BODY_LENS_COLOR, housingColor } from './palette'
import { bodyScale, type FixtureBodyProps } from './types'

const DESIGN_SIZE = 0.18

export function LaserBody({ active, headRef, lensRef, dims }: FixtureBodyProps) {
  return (
    <group ref={headRef} scale={bodyScale(dims, DESIGN_SIZE)}>
      <mesh>
        <boxGeometry args={[0.18, 0.1, 0.16]} />
        <meshStandardMaterial color={housingColor(active)} />
      </mesh>
      <mesh ref={lensRef} position={[0, -0.05 - 0.001, 0]}>
        <sphereGeometry args={[0.025, 12, 8]} />
        <meshBasicMaterial color={BODY_LENS_COLOR} />
      </mesh>
    </group>
  )
}
