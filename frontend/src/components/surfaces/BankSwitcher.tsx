import { cn } from "@/lib/utils"
import type { BankDefinition } from "@/store/surfaces"

interface BankSwitcherProps {
  banks: readonly BankDefinition[]
  /** The layer the editors show. */
  viewedLayer: string | null
  /** The layer the device itself is on, which a dot marks. */
  deviceLayer: string | null
  /** The device is on no layer, as after a restart: a *No layer* chip views what fires there. */
  deviceOnNoLayer?: boolean
  onView: (layer: string | null) => void
}

/**
 * The layer chips (surface-setup plan D13, D17): *Layer A*, *Layer B* — always with its letter. A
 * chip **views** a layer in the editors and sends nothing: the desk changes layer on its own key, or
 * on a *Switch layer* binding. A dot marks the layer the device is on; while it is on none, a *No
 * layer* chip carries the dot.
 */
export function BankSwitcher({ banks, viewedLayer, deviceLayer, deviceOnNoLayer = false, onView }: BankSwitcherProps) {
  if (banks.length === 0) return null
  const chips: { id: string | null; label: string; title: string }[] = [
    ...(deviceOnNoLayer ? [{ id: null, label: "No layer", title: "The desk is on no layer" }] : []),
    ...banks.map((bank) => ({ id: bank.id, label: `Layer ${bank.name}`, title: `The desk is on Layer ${bank.name}` })),
  ]
  return (
    <div className="flex items-center gap-2" role="group" aria-label="View a layer">
      <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">Viewing</span>
      {chips.map((chip) => {
        const onDevice = chip.id == null ? deviceOnNoLayer : deviceLayer === chip.id
        return (
          <button
            key={chip.id ?? ""}
            type="button"
            aria-pressed={viewedLayer === chip.id}
            onClick={() => onView(chip.id)}
            title={onDevice ? chip.title : undefined}
            className={cn(
              "flex items-center gap-1.5 rounded-md border px-2 py-0.5 text-xs",
              viewedLayer === chip.id ? "border-primary bg-primary/10" : "text-muted-foreground hover:bg-accent",
            )}
          >
            {chip.label}
            {onDevice && <span aria-label="the desk's layer" className="size-1.5 rounded-full bg-surface-led" />}
          </button>
        )
      })}
    </div>
  )
}
