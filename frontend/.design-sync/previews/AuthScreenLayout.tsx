import { AuthScreenLayout, Button, Input, Label } from 'lighting-desk-ui'

// The layout is `fixed inset-0`; a transformed ancestor becomes its containing block, so this
// frame holds it inside the cell the way the viewport does in the app. Inline styles because
// the compiled stylesheet carries only the utilities the app itself uses.
const Frame = ({ children }: { children: React.ReactNode }) => (
  <div
    className="relative w-full overflow-hidden rounded-md border"
    style={{ height: 400, transform: 'translateZ(0)' }}
  >
    {children}
  </div>
)

export const Login = () => (
  <Frame>
    <AuthScreenLayout title="Sign in" description="Accounts for this desk live on the machine, not in a project.">
      <form className="space-y-4" onSubmit={(e) => e.preventDefault()}>
        <div className="space-y-1.5">
          <Label htmlFor="auth-user">Username</Label>
          <Input id="auth-user" defaultValue="chris" autoComplete="username" />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="auth-pass">Password</Label>
          <Input id="auth-pass" type="password" defaultValue="••••••••••" autoComplete="current-password" />
        </div>
        <Button type="submit" className="w-full">
          Sign in
        </Button>
      </form>
    </AuthScreenLayout>
  </Frame>
)

export const Setup = () => (
  <Frame>
    <AuthScreenLayout title="Set up this desk" description="Create the first administrator account.">
      <form className="space-y-4" onSubmit={(e) => e.preventDefault()}>
        <div className="space-y-1.5">
          <Label htmlFor="setup-name">Display name</Label>
          <Input id="setup-name" placeholder="Front of house" />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="setup-pass">Password</Label>
          <Input id="setup-pass" type="password" placeholder="At least 8 characters" />
        </div>
        <Button type="submit" className="w-full">
          Create account
        </Button>
      </form>
    </AuthScreenLayout>
  </Frame>
)
