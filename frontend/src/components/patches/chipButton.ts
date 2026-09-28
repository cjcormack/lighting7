export function chipButtonClassName(selected: boolean): string {
  const base = 'transition-colors border'
  return selected
    ? `${base} bg-primary/15 border-primary/40 text-primary`
    : `${base} border-input text-muted-foreground hover:text-foreground hover:border-muted-foreground/60`
}
