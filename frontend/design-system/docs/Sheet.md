---
category: Overlays
---
A Sheet slides in from the right and is the desk's surface for **editing, forms and multi-step
workflows**. Use a Dialog only for confirmations, alerts and status. The structure is fixed:

```tsx
<Sheet open={open} onOpenChange={onOpenChange}>
  <SheetContent className="flex flex-col sm:max-w-md">
    <SheetHeader>
      <SheetTitle>Cue properties</SheetTitle>
      <SheetDescription>Cue 12 · Act 1 — Opening</SheetDescription>
    </SheetHeader>
    <SheetBody>
      {/* scrollable form content — space-y-4 and px-4 pb-4 are built in */}
      <div className="space-y-2">
        <Label htmlFor="cue-name">Name</Label>
        <Input id="cue-name" defaultValue="Band walk-on" />
      </div>
    </SheetBody>
    <SheetFooter className="flex-row justify-end gap-2">
      <SheetClose asChild><Button variant="outline">Cancel</Button></SheetClose>
      <Button>Save</Button>
    </SheetFooter>
  </SheetContent>
</Sheet>
```

Rules:

- `SheetContent` always carries `flex flex-col`; `sm:max-w-md` for standard forms, `sm:max-w-lg`
  for wide content. Sheets are full-screen on mobile by default.
- `SheetBody` is the only scrollable region. Override its padding (`className="space-y-0 p-0"`)
  only when embedding a component that manages its own padding.
- Footer patterns: create/edit → `flex-row justify-end gap-2` with Cancel (`variant="outline"`)
  then the primary action; edit-with-delete → `flex-row justify-between` with a
  `variant="destructive"` Delete on the left and Cancel + Save in a `flex gap-2` on the right;
  equal-width actions → `flex-row gap-2` with `flex-1` on each button. Footer buttons use the
  default size.
- Cancel is wrapped in `<SheetClose asChild>` rather than calling a close handler, so the
  unsaved-changes guard (`useUnsavedChanges(isDirty)`, exported alongside) can intercept it.
- Content nested inside another component's sheet (a sub-view) uses a plain
  `<div className="border-t p-4 flex items-center gap-2">` as its footer — `SheetFooter` must be a
  direct child of `SheetContent`.
