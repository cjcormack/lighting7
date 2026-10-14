import { useSyncExternalStore } from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { onboardingStepStore, type OnboardingStep } from "./onboardingModel"
import { OnboardingDiagnosticsStep } from "./OnboardingDiagnosticsStep"
import { OnboardingProjectsStep } from "./OnboardingProjectsStep"

const STEPS: Record<OnboardingStep, { index: number; title: string; description: string }> = {
  diagnostics: {
    index: 1,
    title: "Send diagnostics?",
    description: "Help find and fix problems on desks like this one.",
  },
  projects: {
    index: 2,
    title: "Your projects",
    description:
      "Bring your shows and venues in from GitHub with cloud sync, or start with a blank project.",
  },
}

/**
 * A new desk's onboarding, after its first admin account is made: diagnostics, then projects. It
 * renders in the router's place, so nothing of the app sits behind it — which is also why it is a
 * page and not an overlay: the device-code dialog it can open has to sit above it.
 */
export default function OnboardingScreen() {
  const step = useSyncExternalStore(
    onboardingStepStore.subscribe,
    onboardingStepStore.getSnapshot,
    onboardingStepStore.getServerSnapshot,
  )
  const { index, title, description } = STEPS[step]

  return (
    <div className="flex min-h-svh items-center justify-center bg-background p-4">
      <Card className="w-full max-w-xl">
        <CardHeader>
          <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
            Step {index} of 2
          </p>
          <CardTitle>{title}</CardTitle>
          <CardDescription>{description}</CardDescription>
        </CardHeader>
        <CardContent>
          {step === "diagnostics" ? (
            <OnboardingDiagnosticsStep onContinue={() => onboardingStepStore.set("projects")} />
          ) : (
            <OnboardingProjectsStep onBack={() => onboardingStepStore.set("diagnostics")} />
          )}
        </CardContent>
      </Card>
    </div>
  )
}
