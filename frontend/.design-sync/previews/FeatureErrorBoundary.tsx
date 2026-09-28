import { FeatureErrorBoundary } from 'lighting-desk-ui'

const StagePreview = () => (
  <div className="rounded-md border bg-card p-3 text-sm">
    <p className="font-medium">Stage view</p>
    <p className="text-xs text-muted-foreground">6 fixtures · Output + Programmer · Cue 12 Warm Wash</p>
    <div className="mt-2 flex gap-1">
      {['#ffb347', '#ffb347', '#2b5fd9', '#2b5fd9', '#8a2be2', '#8a2be2'].map((c, i) => (
        <span key={i} className="size-5 rounded-full border border-black/20" style={{ backgroundColor: c }} />
      ))}
    </div>
  </div>
)

const Broken = ({ message }: { message: string }): never => {
  throw new Error(message)
}

export const Healthy = () => (
  <FeatureErrorBoundary feature="The Stage view">
    <StagePreview />
  </FeatureErrorBoundary>
)

export const ChunkLoadFailure = () => (
  <FeatureErrorBoundary feature="The Stage view" className="m-0">
    <Broken message="Failed to fetch dynamically imported module: /assets/Stage3D-8f3a2c1d.js" />
  </FeatureErrorBoundary>
)

export const RenderFailure = () => (
  <FeatureErrorBoundary feature="The script editor" className="m-0">
    <Broken message="Cannot read properties of undefined (reading 'scriptType')" />
  </FeatureErrorBoundary>
)
