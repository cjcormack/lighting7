import { TimingFields } from '@/components/cues/TimingEditor'
import { SpeedMasterSelect } from '@/components/fx/SpeedMasterSelect'
import { useSpeedMasterLiveQuery } from '@/store/speedMasters'

/** A cue layer's timing and master overrides — the fields [LayerTimingFields] writes. */
export interface LayerTiming {
  delayMs?: number | null
  intervalMs?: number | null
  randomWindowMs?: number | null
  speedMasterUuid?: string | null
  rateSpeedMasterUuid?: string | null
}

/**
 * A cue layer's delay, interval and random window, and its speed and rate master overrides — the cue
 * Looks tab's layer settings popover (apply-model plan §11 question 12), for a layer the picker has
 * just put on and an existing one alike. Each change is handed up as the fields it moved.
 *
 * The overrides are opt-in: unset, every effect in the layer's Look follows its own master, which is
 * what most layers want. Opening one seeds master 1's concrete uuid, so what the select shows is what
 * is stored.
 */
export function LayerTimingFields({ layer, onChange }: { layer: LayerTiming; onChange: (patch: LayerTiming) => void }) {
  const { data: liveMasters } = useSpeedMasterLiveQuery()
  const master1 = liveMasters?.find((m) => m.index === 1)?.uuid
  return (
    <div className="space-y-4" data-layer-timing>
      <TimingFields
        values={{ delayMs: layer.delayMs, intervalMs: layer.intervalMs, randomWindowMs: layer.randomWindowMs }}
        onChange={(values) =>
          onChange({ delayMs: values.delayMs ?? null, intervalMs: values.intervalMs ?? null, randomWindowMs: values.randomWindowMs ?? null })
        }
      />
      {layer.speedMasterUuid == null ? (
        <button
          type="button"
          disabled={master1 == null}
          onClick={() => master1 != null && onChange({ speedMasterUuid: master1 })}
          className="text-xs text-muted-foreground transition-colors hover:text-foreground disabled:opacity-50"
        >
          + Override speed master for this layer
        </button>
      ) : (
        <div className="space-y-1.5">
          <SpeedMasterSelect value={layer.speedMasterUuid} onChange={(uuid) => onChange({ speedMasterUuid: uuid })} />
          <button
            type="button"
            onClick={() => onChange({ speedMasterUuid: null })}
            className="text-xs text-muted-foreground transition-colors hover:text-foreground"
          >
            Remove override — each effect follows its own master
          </button>
        </div>
      )}
      {/* A separate control: a Look can hold both beat and wall-clock effects, and pinning one
          axis says nothing about the other. */}
      {layer.rateSpeedMasterUuid == null ? (
        <button
          type="button"
          disabled={master1 == null}
          onClick={() => master1 != null && onChange({ rateSpeedMasterUuid: master1 })}
          className="text-xs text-muted-foreground transition-colors hover:text-foreground disabled:opacity-50"
        >
          + Override rate master for this layer
        </button>
      ) : (
        <div className="space-y-1.5">
          <SpeedMasterSelect
            value={layer.rateSpeedMasterUuid}
            onChange={(uuid) => onChange({ rateSpeedMasterUuid: uuid })}
            label="Rate master"
            description="Only wall-clock effects in this look are affected."
          />
          <button
            type="button"
            onClick={() => onChange({ rateSpeedMasterUuid: null })}
            className="text-xs text-muted-foreground transition-colors hover:text-foreground"
          >
            Remove override — each effect follows its own rate master
          </button>
        </div>
      )}
    </div>
  )
}
