import { useState } from "react"
import { Loader2 } from "lucide-react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"
import { useUpdateTelemetryMutation } from "@/store/telemetry"

const CHOICES = [
  {
    value: true,
    label: "Send diagnostics",
    detail:
      "Errors, logs, performance traces and metrics from this desk and its browsers go to the lighting7 developers' Sentry. Tokens and passwords are removed first.",
  },
  {
    value: false,
    label: "Don't send",
    detail: "Nothing leaves this desk. You can change this later in Install settings.",
  },
] as const

/**
 * The install's telemetry switch (`TelemetryCard`'s), asked once as a choice rather than left at
 * the build's default: nothing is picked until the admin picks, and Continue writes the answer.
 */
export function OnboardingDiagnosticsStep({ onContinue }: { onContinue: () => void }) {
  const [update, { isLoading: saving }] = useUpdateTelemetryMutation()
  const [choice, setChoice] = useState<boolean | null>(null)

  const handleContinue = async () => {
    if (choice == null) return
    // A failure is errorToastMiddleware's to report, and keeps the operator on this step.
    const answer = await update({ enabled: choice }).unwrap().catch(() => null)
    if (answer == null) return
    if (choice && !answer.enabled) {
      toast.warning("sentry.enabled = false in local.conf keeps telemetry off on this machine")
    }
    onContinue()
  }

  return (
    <div className="space-y-4">
      <div role="radiogroup" aria-label="Diagnostics" className="space-y-2">
        {CHOICES.map(({ value, label, detail }) => (
          <button
            key={label}
            type="button"
            role="radio"
            aria-checked={choice === value}
            onClick={() => setChoice(value)}
            className={cn(
              "w-full rounded-md border p-3 text-left transition-colors",
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
              choice === value ? "border-primary bg-primary/5" : "hover:bg-muted/50",
            )}
          >
            <span className="block text-sm font-medium">{label}</span>
            <span className="block text-sm text-muted-foreground">{detail}</span>
          </button>
        ))}
      </div>
      <div className="flex justify-end">
        <Button onClick={() => void handleContinue()} disabled={saving || choice == null}>
          {saving && <Loader2 className="size-4 animate-spin" />}
          Continue
        </Button>
      </div>
    </div>
  )
}
