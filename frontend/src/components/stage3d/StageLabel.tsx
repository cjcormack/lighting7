import { Html } from '@react-three/drei'

interface StageLabelProps {
  position: [number, number, number]
  children: string
}

export function StageLabel({ position, children }: StageLabelProps) {
  return (
    <Html
      position={position}
      center
      distanceFactor={10}
      pointerEvents="none"
      zIndexRange={[100, 0]}
    >
      <div className="whitespace-nowrap rounded bg-background/85 px-1.5 py-0.5 text-[11px] font-medium text-foreground shadow-sm backdrop-blur">
        {children}
      </div>
    </Html>
  )
}
