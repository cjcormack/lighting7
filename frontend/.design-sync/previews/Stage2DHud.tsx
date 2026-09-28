import { Stage2DHud } from 'lighting-desk-ui'

// StageProjection values mirror lib/stageProjection's STAGE_PROJECTIONS.
const PLAN = {
  id: 'plan',
  label: 'Plan',
  h: { axis: 'x', sign: 1 },
  v: { axis: 'y', sign: -1 },
  depth: 'z',
  hAxisLabel: 'Stage right (X)',
  vAxisLabel: 'Upstage (Y)',
} as const

const SIDE = {
  id: 'side',
  label: 'Side',
  h: { axis: 'y', sign: 1 },
  v: { axis: 'z', sign: -1 },
  depth: 'x',
  hAxisLabel: 'Upstage (Y)',
  vAxisLabel: 'Height (Z)',
} as const

type Projection = Parameters<typeof Stage2DHud>[0]['projection']

// The HUD is absolutely positioned chrome, so it sits over a stand-in for the
// stage plot: a dark box with a metre grid and a few fixture dots. The box's
// size and background are inline styles on purpose: the shipped stylesheet only
// carries utilities the app itself uses, so preview-only classes such as
// `h-60` / `bg-neutral-900` don't exist in it.
function Plot({ children }: { children: React.ReactNode }) {
  return (
    <div
      className="relative overflow-hidden rounded-md border border-border"
      style={{
        width: 420,
        height: 240,
        backgroundColor: '#171717',
        backgroundImage:
          'linear-gradient(rgba(255,255,255,0.06) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.06) 1px, transparent 1px)',
        backgroundSize: '24px 24px',
      }}
    >
      <div style={{ position: 'absolute', left: 40, right: 40, top: 60, height: 1, background: '#525252' }} />
      {[60, 130, 200, 270, 340].map((x) => (
        <div
          key={x}
          style={{
            position: 'absolute',
            left: x,
            top: 55,
            width: 10,
            height: 10,
            borderRadius: 9999,
            background: '#fcd34d',
            boxShadow: '0 0 10px 2px rgba(252,211,77,0.5)',
          }}
        />
      ))}
      {children}
    </div>
  )
}

export const PlanWithCursor = () => (
  <Plot>
    <Stage2DHud
      projection={PLAN as unknown as Projection}
      cursor={{ h: 3.25, v: 2.5 }}
      snapStepM={0.5}
      onFit={() => {}}
      onZoomIn={() => {}}
      onZoomOut={() => {}}
    />
  </Plot>
)

export const SideWithNotice = () => (
  <Plot>
    <Stage2DHud
      projection={SIDE as unknown as Projection}
      cursor={{ h: -1.2, v: -4.75 }}
      snapStepM={0.1}
      onFit={() => {}}
      onZoomIn={() => {}}
      onZoomOut={() => {}}
      notice="Region depth can't be adjusted in the side elevation — switch to Plan."
    />
  </Plot>
)

export const PointerOutsideSnapOff = () => (
  <Plot>
    <Stage2DHud
      projection={PLAN as unknown as Projection}
      cursor={null as unknown as { h: number; v: number }}
      snapStepM={null as unknown as number}
      onFit={() => {}}
      onZoomIn={() => {}}
      onZoomOut={() => {}}
    />
  </Plot>
)
