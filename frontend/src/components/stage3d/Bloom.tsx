import { Bloom as PpBloom, EffectComposer } from '@react-three/postprocessing'

// Bloom is essential — the scene looks flat without it. Wrap EffectComposer
// here so Stage3D can drop in a single <Bloom /> without ceremony.
export function Bloom() {
  return (
    <EffectComposer>
      <PpBloom luminanceThreshold={0.15} intensity={1.7} radius={0.5} />
    </EffectComposer>
  )
}
