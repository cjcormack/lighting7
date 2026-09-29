import { useCallback } from 'react'
import { useThree } from '@react-three/fiber'
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
//
// The composer is rebuilt — a new instance with new passes — whenever the default camera changes,
// which is every viewpoint switch (stage-view plan session 1). The rebuild lands in an effect after
// the switch's own frame, which the *old* composer drew through the old camera; on a `demand`
// frameloop nothing else asks for another, so the view sat on the previous camera's picture while
// the labels had already moved. The ref is handed each new instance, and asks for the frame.
export function Bloom() {
  const invalidate = useThree((s) => s.invalidate)
  const onComposer = useCallback(
    (composer: unknown) => {
      if (composer != null) invalidate()
    },
    [invalidate],
  )
  return (
    <EffectComposer ref={onComposer} multisampling={0}>
      <PpBloom luminanceThreshold={0.15} intensity={1.7} radius={0.5} />
    </EffectComposer>
  )
}
