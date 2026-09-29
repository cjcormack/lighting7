import { Bloom as PpBloom, EffectComposer } from '@react-three/postprocessing'

// Bloom is essential — the scene looks flat without it. Wrap EffectComposer
// here so Stage3D can drop in a single <Bloom /> without ceremony.
//
// `multisampling={0}` is not a quality knob, it is the memory budget.
// @react-three/postprocessing defaults to 8× MSAA on half-float targets, and at
// a Retina full-window size the composer's colour target alone ran to hundreds
// of MB — the likeliest cause of Safari's "reloaded because it was using
// significant memory" on the Stage view (stage-view plan, session 0). Beams and
// pools are soft additive shapes that MSAA does nothing for; the hard edges it
// did smooth are the bodies and the grid, which the canvas's own `antialias`
// no longer reaches once the composer draws the scene — an accepted cost.
export function Bloom() {
  return (
    <EffectComposer multisampling={0}>
      <PpBloom luminanceThreshold={0.15} intensity={1.7} radius={0.5} />
    </EffectComposer>
  )
}
