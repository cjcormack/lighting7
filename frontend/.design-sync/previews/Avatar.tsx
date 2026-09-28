import { Avatar, AvatarFallback, Badge } from 'lighting-desk-ui'

// `bg-muted` is a near-white token in the light theme, so a bare avatar on a white
// card reads as floating initials. Every cell puts the avatars on a card/border
// surface — which is also where they actually appear in the app (the user menu, a
// project's operator list).

export const Default = () => (
  <div className="flex items-center gap-3 rounded-lg border p-3">
    <Avatar>
      <AvatarFallback>CC</AvatarFallback>
    </Avatar>
    <div className="min-w-0">
      <div className="text-sm font-medium">Chris Cormack</div>
      <div className="text-xs text-muted-foreground">Signed in on this desk</div>
    </div>
    <Badge variant="secondary" className="ml-auto">
      Admin
    </Badge>
  </div>
)

export const Group = () => (
  <div className="space-y-2 rounded-lg border p-3">
    {[
      ['CC', 'Chris Cormack', 'Admin'],
      ['LD', 'Lighting Designer', 'Operator'],
      ['SM', 'Stage Manager', 'Operator'],
    ].map(([initials, name, role]) => (
      <div key={initials} className="flex items-center gap-3">
        <Avatar>
          <AvatarFallback>{initials}</AvatarFallback>
        </Avatar>
        <span className="text-sm">{name}</span>
        <span className="ml-auto text-xs text-muted-foreground">{role}</span>
      </div>
    ))}
  </div>
)

export const Sizes = () => (
  <div className="flex items-end gap-3 rounded-lg border p-3">
    <Avatar className="size-6 text-xs">
      <AvatarFallback>CC</AvatarFallback>
    </Avatar>
    <Avatar>
      <AvatarFallback>CC</AvatarFallback>
    </Avatar>
    <Avatar className="size-10">
      <AvatarFallback>CC</AvatarFallback>
    </Avatar>
    <Avatar className="size-14 text-lg">
      <AvatarFallback>CC</AvatarFallback>
    </Avatar>
  </div>
)

export const Accent = () => (
  <div className="flex items-center gap-3 rounded-lg border p-3">
    <Avatar>
      <AvatarFallback className="bg-primary text-primary-foreground">OP</AvatarFallback>
    </Avatar>
    <Avatar>
      <AvatarFallback className="bg-destructive text-primary-foreground">!</AvatarFallback>
    </Avatar>
    <Avatar>
      <AvatarFallback className="bg-accent text-accent-foreground">SM</AvatarFallback>
    </Avatar>
    <span className="text-xs text-muted-foreground">
      The fallback takes any surface token
    </span>
  </div>
)
