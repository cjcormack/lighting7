import { Slider, Label } from 'lighting-desk-ui'

export const Default = () => (
  <div className="grid w-64 gap-2">
    <div className="flex items-center justify-between text-sm">
      <Label>Intensity</Label>
      <span className="font-mono text-xs text-muted-foreground">65%</span>
    </div>
    <Slider defaultValue={[65]} className="w-64" aria-label="Intensity" />
  </div>
)

export const Values = () => (
  <div className="grid w-64 gap-4">
    <Slider defaultValue={[0]} className="w-64" aria-label="Off" />
    <Slider defaultValue={[35]} className="w-64" aria-label="Low" />
    <Slider defaultValue={[100]} className="w-64" aria-label="Full" />
  </div>
)

export const Range = () => (
  <div className="grid w-64 gap-2">
    <div className="flex items-center justify-between text-sm">
      <Label>Tempo window (BPM)</Label>
      <span className="font-mono text-xs text-muted-foreground">60 – 180</span>
    </div>
    <Slider defaultValue={[60, 180]} min={20} max={300} step={1} minStepsBetweenThumbs={5} className="w-64" aria-label="Tempo window" />
  </div>
)

export const Stepped = () => (
  <div className="grid w-64 gap-2">
    <div className="flex items-center justify-between text-sm">
      <Label>Pan</Label>
      <span className="font-mono text-xs text-muted-foreground">-90°</span>
    </div>
    <Slider defaultValue={[-90]} min={-270} max={270} step={15} className="w-64" aria-label="Pan" />
  </div>
)

export const Disabled = () => (
  <div className="grid w-64 gap-2">
    <Label>Parked at 40%</Label>
    <Slider defaultValue={[40]} disabled className="w-64" aria-label="Parked" />
  </div>
)

export const Vertical = () => (
  <div className="flex h-44 items-end gap-6">
    <Slider orientation="vertical" defaultValue={[80]} className="h-44" aria-label="Red" />
    <Slider orientation="vertical" defaultValue={[45]} className="h-44" aria-label="Green" />
    <Slider orientation="vertical" defaultValue={[10]} className="h-44" aria-label="Blue" />
    <Slider orientation="vertical" defaultValue={[100]} className="h-44" aria-label="White" />
  </div>
)
