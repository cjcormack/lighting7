import { Badge, Button, Input, Label, Tabs, TabsContent, TabsList, TabsTrigger } from 'lighting-desk-ui'
import { Layers, Settings2, Sparkles, Users } from 'lucide-react'

// Tabs switch between readings of one thing. The install settings page and the
// profile sheet both use them: a list of triggers over a panel, `defaultValue`
// picking the open panel, and each panel owning its own content.
export const InstallSettings = () => (
  <Tabs defaultValue="patches" className="w-full max-w-lg">
    <TabsList>
      <TabsTrigger value="general">General</TabsTrigger>
      <TabsTrigger value="patches">Patches</TabsTrigger>
      <TabsTrigger value="users">Users</TabsTrigger>
      <TabsTrigger value="updates">Updates</TabsTrigger>
    </TabsList>
    <TabsContent value="general" className="rounded-lg border p-4">
      <p className="text-muted-foreground text-sm">Desk name, default project, and boot behaviour.</p>
    </TabsContent>
    <TabsContent value="patches" className="space-y-3 rounded-lg border p-4">
      <div className="flex items-center justify-between">
        <div>
          <div className="text-sm font-medium">Patches</div>
          <p className="text-muted-foreground text-sm">Fixture models and the channels they occupy.</p>
        </div>
        <Button size="sm">New patch</Button>
      </div>
      <ul className="divide-y text-sm">
        <li className="flex items-center justify-between py-2">
          <span>Robe Pointe · 16-bit</span>
          <Badge variant="secondary">24 ch</Badge>
        </li>
        <li className="flex items-center justify-between py-2">
          <span>Martin MAC Aura XB</span>
          <Badge variant="secondary">25 ch</Badge>
        </li>
        <li className="flex items-center justify-between py-2">
          <span>ETC Source Four LED S2</span>
          <Badge variant="secondary">7 ch</Badge>
        </li>
      </ul>
    </TabsContent>
    <TabsContent value="users" className="rounded-lg border p-4">
      <p className="text-muted-foreground text-sm">Desk accounts and roles.</p>
    </TabsContent>
    <TabsContent value="updates" className="rounded-lg border p-4">
      <p className="text-muted-foreground text-sm">Version 1.8.2 · up to date.</p>
    </TabsContent>
  </Tabs>
)

// Triggers can carry an icon; the programmer's three readings of one live
// object are the canonical example. The open panel holds a short form.
export const WithIcons = () => (
  <Tabs defaultValue="layers" className="w-full max-w-lg">
    <TabsList className="grid w-full grid-cols-3">
      <TabsTrigger value="values">
        <Settings2 />
        Values
      </TabsTrigger>
      <TabsTrigger value="layers">
        <Layers />
        Layers
      </TabsTrigger>
      <TabsTrigger value="fx">
        <Sparkles />
        Effects
      </TabsTrigger>
    </TabsList>
    <TabsContent value="values" className="rounded-lg border p-4">
      <p className="text-muted-foreground text-sm">14 fixtures · 38 values set.</p>
    </TabsContent>
    <TabsContent value="layers" className="space-y-4 rounded-lg border p-4">
      <div className="grid gap-2">
        <Label htmlFor="layer-name">Look</Label>
        <Input id="layer-name" defaultValue="Warm Wash" readOnly />
      </div>
      <div className="grid grid-cols-2 gap-4">
        <div className="grid gap-2">
          <Label htmlFor="layer-amount">Amount (%)</Label>
          <Input id="layer-amount" type="number" defaultValue="100" />
        </div>
        <div className="grid gap-2">
          <Label htmlFor="layer-targets">Targets</Label>
          <div className="flex h-9 items-center gap-1.5 text-sm">
            <Users className="text-muted-foreground size-4" />
            Front wash · 6 heads
          </div>
        </div>
      </div>
    </TabsContent>
    <TabsContent value="fx" className="rounded-lg border p-4">
      <p className="text-muted-foreground text-sm">2 running · Colour chase, Dimmer sine.</p>
    </TabsContent>
  </Tabs>
)
