## How to build with LightingDeskUi

This is the UI of a DMX lighting desk: an operator records **cues** into **cue stacks**, runs them
with GO, busks with **looks** and **templates** on **fixtures** and **groups**, and follows
**speed masters** (tempo). Use that vocabulary in copy ("Record cue", "Act 1 — Opening",
"Front wash", "Warm Wash", "8 s fade"), not generic placeholder text.

### Wrap and theme

- Put `<TooltipProvider>` around the whole tree once. Tooltips throw without it; nothing else
  needs a provider (no router, no store, no theme provider).
- Light is the default. For the dark desk theme add the class `dark` to an ancestor (the app puts it
  on `<html>`); every token below flips automatically, so never hard-code dark colours.
- Give the page `bg-background text-foreground` (the app does this on `<body>`).

### Styling idiom: Tailwind utilities on semantic tokens

Components style themselves; you write **Tailwind v4 utility classes** for layout glue, and you
colour only through the semantic token classes. The stylesheet is a **compiled** Tailwind build, not
a runtime one, so a class it does not contain silently does nothing — no error, the element just
has no style. The families below are all present and are the safe vocabulary; arbitrary values
(`mt-[13px]`) are not, so grep `_ds_bundle.css` before reaching outside them. Never colour with raw
palette classes (`bg-blue-500`, `text-gray-600`) or hex values: they do not follow the dark theme.

| Family | Use these classes |
|---|---|
| Surfaces | `bg-background` `bg-card` `bg-popover` `bg-muted` `bg-accent` `bg-secondary` `bg-primary` `bg-destructive` `hover:bg-accent` `hover:bg-muted` |
| Text | `text-foreground` `text-muted-foreground` `text-card-foreground` `text-primary` `text-primary-foreground` `text-destructive` |
| Borders | `border` `border-t` `border-b` `border-border` `border-input` `divide-y` |
| Radius and depth | `rounded-md` `rounded-lg` `rounded-xl` `rounded-full` (from `--radius`, 0.625rem) · `shadow-sm` `shadow-md` `shadow-lg` |
| Spacing | `gap-2` `gap-3` `gap-4` `space-y-2` `space-y-4` `p-4` `px-4` `py-2` `pb-4` |
| Type | `text-xs` `text-sm` `text-base` `text-lg` `text-xl` `text-2xl` `font-medium` `font-semibold` `font-bold` `font-mono` `tabular-nums` `tracking-wide` `uppercase` `truncate` `leading-relaxed` `text-center` |
| Layout | `flex` `flex-col` `flex-row` `flex-1` `flex-wrap` `items-center` `items-start` `justify-between` `justify-center` `justify-end` `grid` `grid-cols-2` `grid-cols-3` `grid-cols-4` `col-span-2` |
| Sizing | `w-full` `h-full` `min-w-0` `min-h-screen` `max-w-sm` `max-w-md` `max-w-2xl` `max-w-4xl` `mx-auto` `overflow-y-auto` `overflow-hidden` |
| Position | `relative` `absolute` `fixed` `sticky` `inset-0` `top-0` `z-10` `z-50` |
| Responsive | `sm:` `md:` `lg:` on grid columns, flex direction and max width: `sm:grid-cols-2` `md:grid-cols-3` `lg:grid-cols-4` `sm:flex-row` `sm:max-w-md` |
| Focus ring | `focus-visible:ring-ring/50` (what the primitives use) |

The underlying CSS custom properties, if you need a raw value: `--background` `--foreground`
`--card` `--popover` `--primary` `--primary-foreground` `--secondary` `--muted`
`--muted-foreground` `--accent` `--destructive` `--border` `--input` `--ring` `--radius`
`--chart-1`…`--chart-5` `--sidebar` `--sidebar-primary`. All are oklch and defined twice, under
`:root` and under `.dark`.

Variant props carry the design language where a component has them: `Button` `variant`
(`default` `secondary` `outline` `ghost` `destructive` `link`) and `size` (`sm` `default` `lg`
`icon-sm` `icon` `icon-lg`); `Badge` `variant`; `Alert` `variant` (`default` `destructive`).
Prefer a variant to restyling a component with classes.

### Component rules that matter

- **Sheet for editing, Dialog for confirming.** Any form or multi-step flow is a `Sheet`
  (`SheetContent className="flex flex-col sm:max-w-md"` → `SheetHeader` → `SheetBody` →
  `SheetFooter className="flex-row justify-end gap-2"`, Cancel as `variant="outline"`). A
  `Dialog` / `AlertDialog` is only for confirmations, alerts and status. Read `Sheet.prompt.md`.
- Compound primitives are **flat exports**, not dotted: `Card` + `CardHeader` + `CardTitle` +
  `CardDescription` + `CardAction` + `CardContent` + `CardFooter`; `Select` + `SelectTrigger` +
  `SelectValue` + `SelectContent` + `SelectItem`; `Tabs` + `TabsList` + `TabsTrigger` +
  `TabsContent`; `Table` + `TableHeader` + `TableRow` + `TableHead` + `TableBody` + `TableCell`.
  There is no `Card.Header`.
- Icons: the library does not export an icon set. Components that need one render their own.
  In a `Button`, an inline `<svg>` child before the label is sized and gapped automatically.
- Small desk-specific pieces exist and beat re-creating them: `BuskLabel` (region label),
  `LookNameBadge`, `TimingBadge`, `InlineEditField` (click-to-edit text), `CollapsiblePanel`,
  `TruncateStart` (keeps the end of a long name visible), `ShowLockControl`,
  `OffPlayheadBanner`, `OutOfOrderBanner`.

### Where the truth lives

- `styles.css` → `_ds_bundle.css`: the compiled stylesheet. Tokens are at the top under `:root`
  and `.dark`; every utility class you may use is defined in it — if a class is not in that file it
  does not exist here.
- `components/<group>/<Name>/<Name>.prompt.md` for usage and `<Name>.d.ts` for the exact props.

### One idiomatic build

```jsx
const { TooltipProvider, Card, CardHeader, CardTitle, CardDescription, CardAction,
        CardContent, CardFooter, Badge, Button } = window.LightingDeskUi;

<TooltipProvider>
  <main className="bg-background text-foreground p-4 space-y-4">
    <Card className="w-full max-w-sm">
      <CardHeader>
        <CardTitle>Act 1 — Opening</CardTitle>
        <CardDescription>12 cues · last run Tuesday 19:42</CardDescription>
        <CardAction><Badge variant="secondary">Live</Badge></CardAction>
      </CardHeader>
      <CardContent>
        <p className="text-sm text-muted-foreground">
          House to half, then a slow 8 s fade up on the front wash while the band walks on.
        </p>
      </CardContent>
      <CardFooter className="justify-end gap-2">
        <Button variant="outline" size="sm">Open</Button>
        <Button size="sm">GO</Button>
      </CardFooter>
    </Card>
  </main>
</TooltipProvider>
```
