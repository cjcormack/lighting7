import { Alert, AlertTitle, AlertDescription } from 'lighting-desk-ui'
import { Info, AlertTriangle, WifiOff, CheckCircle2 } from 'lucide-react'

export const Default = () => (
  <Alert className="max-w-sm">
    <Info />
    <AlertTitle>Blind is on</AlertTitle>
    <AlertDescription>Programmer changes are previewed here and not sent to the rig.</AlertDescription>
  </Alert>
)

export const Destructive = () => (
  <Alert variant="destructive" className="max-w-sm">
    <AlertTriangle />
    <AlertTitle>Could not record cue</AlertTitle>
    <AlertDescription>The Include target was deleted in another tab. Record into a stack instead.</AlertDescription>
  </Alert>
)

export const TextHeavy = () => (
  <Alert variant="destructive" className="max-w-sm">
    <WifiOff />
    <AlertTitle>Desk offline — output frozen at the last frame</AlertTitle>
    <AlertDescription>
      <p>
        The WebSocket to lighting7 closed and has not reopened. Faders and GO are disabled until the link is back.
      </p>
      <ul className="list-inside list-disc text-sm">
        <li>Check the desk is powered and on the same network.</li>
        <li>Universe 1 and 2 will resume from the last cooked frame.</li>
        <li>Speed masters keep running locally.</li>
      </ul>
    </AlertDescription>
  </Alert>
)

export const TitleOnly = () => (
  <div className="grid max-w-sm gap-3">
    <Alert>
      <CheckCircle2 />
      <AlertTitle>Project synced to GitHub 2 minutes ago</AlertTitle>
    </Alert>
    <Alert>
      <AlertTitle>No icon: 3 cues out of order — Fix Order to renumber</AlertTitle>
    </Alert>
  </div>
)
