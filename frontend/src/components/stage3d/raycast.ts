// No-op raycast for visual-only meshes — cones, floor pools, the invisible
// stage-box helper. Three's default raycast triangle-tests every mesh on every
// pointer move; assigning this skips the test entirely. R3F treats `undefined`
// as "use default", so the explicit no-op is the documented opt-out.
export const NO_RAYCAST = () => {}
