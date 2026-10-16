import { useState, type KeyboardEvent } from 'react'
import { useNavigate } from 'react-router'
import { Command } from 'cmdk'
import { ChevronDown, Search } from 'lucide-react'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { cn } from '@/lib/utils'
import type { ProjectSummary } from '@/api/projectApi'
import type { VenueHoldState } from '@/api/venueHoldApi'
import CreateProjectDialog from '@/CreateProjectDialog'
import ProjectSwitchConfirmDialog from '@/ProjectSwitchConfirmDialog'
import { useIsNavAdmin } from '@/navigation'
import { projectActions, type ProjectAction } from '@/lib/projectActions'
import {
  RIG_DOT_LABEL,
  pinChipOf,
  rigDotOf,
  switcherDetailOf,
  switcherGroupsOf,
  switcherOrderOf,
  type PinChip as PinChipModel,
  type RigDot as RigDotState,
} from '@/lib/projectSwitch'
import { useProjectListQuery } from '@/store/projects'
import { useRigSafety } from '@/store/rigSafety'
import { useIsDeskConnected } from '@/store/status'
import { useVenueHold } from '@/store/venueHold'
import { VenueTag } from '../venue/VenueTag'

/**
 * The app bar's switcher (navigation plan D1, §4.1–§4.2): a button naming the project **on the rig**
 * — the server's `isCurrent` (P4), never the viewed project — with a dot for whether the rig is
 * sending, then the pin chip for a show on a venue. Its menu finds a show or a venue, opens its home
 * and makes it current through the one confirmation (`ProjectSwitchConfirmDialog`).
 *
 * Immersive mode hides the app bar and with it this; ⌘K's *Make … current* is the door there.
 */
export function AppBarSwitcher() {
  const navigate = useNavigate()
  const { data: projects } = useProjectListQuery()
  const hold = useVenueHold()
  const safety = useRigSafety()
  const connected = useIsDeskConnected()
  const [open, setOpen] = useState(false)
  const [confirming, setConfirming] = useState<ProjectSummary | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [createKind, setCreateKind] = useState<'SHOW' | 'VENUE'>('SHOW')

  const current = projects?.find((p) => p.isCurrent) ?? null
  if (current == null) return null

  const dot = rigDotOf(current.id, hold, safety, connected)
  const chip = pinChipOf(current, hold)

  const go = (path: string) => {
    setOpen(false)
    navigate(path)
  }
  const makeCurrent = (project: ProjectSummary) => {
    setOpen(false)
    setConfirming(project)
  }
  const create = (kind: 'SHOW' | 'VENUE') => {
    setOpen(false)
    setCreateKind(kind)
    setCreateOpen(true)
  }

  return (
    <>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <button
            type="button"
            data-app-switcher=""
            data-rig-dot={dot}
            aria-label={`On the rig: ${current.name}. Switch project`}
            title={`${current.name} — ${RIG_DOT_LABEL[dot]}`}
            className="flex h-8 min-w-0 shrink-0 items-center gap-1.5 rounded-md @[440px]:shrink px-1.5 text-sm font-semibold hover:bg-primary-foreground/10 aria-expanded:bg-primary-foreground/10"
          >
            <RigDot state={dot} />
            {/* Below 440px of header the name folds and the dot is the button: at 375 the chips leave
                it 65px. The name is the button's label and the menu's first row. */}
            <span className="hidden min-w-0 truncate @[440px]:block">{current.name}</span>
            {current.kind === 'VENUE' && <VenueTag className="shrink-0 border-primary-foreground/50 bg-primary-foreground/15 text-primary-foreground dark:text-primary-foreground" />}
            <ChevronDown className="size-3.5 shrink-0 opacity-70" />
          </button>
        </PopoverTrigger>
        <PopoverContent align="start" collisionPadding={8} className="w-[min(400px,calc(100vw-16px))] p-0">
          <SwitcherMenu
            projects={projects ?? []}
            hold={hold}
            dot={dot}
            onNavigate={go}
            onMakeCurrent={makeCurrent}
            onAllProjects={() => go('/projects')}
            onCreate={create}
          />
        </PopoverContent>
      </Popover>
      {chip && <PinChip chip={chip} onOpen={() => navigate(`/projects/${current.id}`)} />}
      <ProjectSwitchConfirmDialog project={confirming} onClose={() => setConfirming(null)} />
      <CreateProjectDialog
        open={createOpen}
        setOpen={setCreateOpen}
        kind={createKind}
        onCreated={(project) => navigate(`/projects/${project.id}`)}
      />
    </>
  )
}

/** The rig's dot, on the app bar's primary ground or the menu's light one. */
function RigDot({ state, ground = 'bar' }: { state: RigDotState; ground?: 'bar' | 'menu' }) {
  const bar = ground === 'bar'
  return (
    <span
      aria-hidden
      title={bar ? undefined : RIG_DOT_LABEL[state]}
      className={cn(
        'size-2 shrink-0 rounded-full',
        state === 'sending' && (bar ? 'bg-green-400' : 'bg-green-500'),
        state === 'held' && (bar ? 'bg-amber-400' : 'bg-amber-500'),
        state === 'blackout' && (bar ? 'border-[1.5px] border-primary-foreground' : 'border-[1.5px] border-foreground'),
        state === 'offline' && (bar ? 'bg-primary-foreground/40' : 'bg-muted-foreground/50'),
      )}
    />
  )
}

/** A show's pin, after the button: `TCH venue v7 · held`, in the behind banner's colours; opens the show's home. */
function PinChip({ chip, onOpen }: { chip: PinChipModel; onOpen: () => void }) {
  return (
    <button
      type="button"
      onClick={onOpen}
      data-pin-chip={chip.tone}
      title={`${chip.label}${chip.status ? ` · ${chip.status}` : ''} — open the show's home`}
      className={cn(
        // Below 920px of header it truncates beside the switcher (the chips grow at 760); the dot and the banner say held there.
        'hidden h-7 min-w-0 shrink items-center gap-1 rounded-md border bg-card px-2 text-[11px] font-medium text-foreground shadow-sm @[920px]:inline-flex',
        chip.tone === 'held' && 'border-amber-500/70',
        chip.tone === 'output' && 'border-amber-500/70',
        chip.tone === 'missing' && 'border-red-500/70',
      )}
    >
      <span className="min-w-0 truncate">{chip.label}</span>
      {chip.status && (
        <span
          className={cn(
            'shrink-0',
            (chip.tone === 'held' || chip.tone === 'output') && 'text-amber-600 dark:text-amber-400',
            chip.tone === 'missing' && 'text-red-600 dark:text-red-400',
            chip.tone === 'drawing' && 'text-muted-foreground',
          )}
        >
          · {chip.status}
        </span>
      )}
    </button>
  )
}

const valueOf = (p: ProjectSummary) => `project-${p.id}`

/**
 * The switcher's menu (§4.2): search, *On the rig*, Shows and Venues, each sorted by name. A row
 * offers its project's *rig* actions (`lib/projectActions.ts`, P3) — Open, Make current, Settings.
 * Enter opens the highlighted row's home — the first match while typing — and ⌥Enter makes it
 * current.
 */
export function SwitcherMenu({
  projects,
  hold,
  dot,
  onNavigate,
  onMakeCurrent,
  onAllProjects,
  onCreate,
}: {
  projects: readonly ProjectSummary[]
  hold: VenueHoldState
  /** The switcher button's dot, which the *On the rig* row repeats. */
  dot: RigDotState
  onNavigate: (path: string) => void
  onMakeCurrent: (project: ProjectSummary) => void
  onAllProjects: () => void
  onCreate: (kind: 'SHOW' | 'VENUE') => void
}) {
  const isAdmin = useIsNavAdmin()
  const rigActions = (project: ProjectSummary): ProjectAction[] =>
    projectActions(project, {
      place: 'switcher',
      isAdmin,
      host: { navigate: onNavigate, open: () => onMakeCurrent(project) },
    // A row has room for Open and Make current; Settings is a press away on the home, the sidebar and ⌘K.
    }).filter((a) => a.group === 'rig' && a.visible && a.id !== 'settings')
  const actionOf = (project: ProjectSummary, id: ProjectAction['id']) => rigActions(project).find((a) => a.id === id)
  const [query, setQuery] = useState('')
  const groups = switcherGroupsOf(projects, query)
  const order = switcherOrderOf(groups)
  const [highlight, setHighlight] = useState('')
  const highlighted = order.find((p) => valueOf(p) === highlight) ?? order[0] ?? null

  // A new search highlights its first row: an unknown highlight falls back to `order[0]`.
  const search = (next: string) => {
    setQuery(next)
    setHighlight('')
  }

  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key !== 'Enter' || !e.altKey) return
    // ⌥Enter: make it current. cmdk reads Enter after this handler and skips a prevented one.
    e.preventDefault()
    const makeCurrent = highlighted && actionOf(highlighted, 'make-current')
    if (makeCurrent?.enabled) makeCurrent.run()
  }

  const row = (project: ProjectSummary) => {
    const actions = rigActions(project)
    return (
      <Command.Item
        key={project.id}
        value={valueOf(project)}
        onSelect={() => actions.find((a) => a.id === 'open')?.run()}
        data-switcher-row={project.id}
        className="group flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm aria-selected:bg-accent aria-selected:text-accent-foreground"
      >
        {project.isCurrent ? <RigDot state={dot} ground="menu" /> : <span className="w-2 shrink-0" />}
        <span className="min-w-0 truncate font-medium">{project.name}</span>
        {project.kind === 'VENUE' && <VenueTag className="shrink-0" />}
        <span className="min-w-0 flex-1 truncate text-xs text-muted-foreground">· {switcherDetailOf(project, hold)}</span>
        {actions.map((action) =>
          action.enabled ? (
            <RowButton key={action.id} label={action.label} onClick={action.run} />
          ) : (
            <span
              key={action.id}
              data-switcher-refusal={action.id}
              className="shrink-0 text-[11px] text-muted-foreground"
              title={action.reason ?? undefined}
            >
              {action.refusalLabel ?? action.label}
            </span>
          ),
        )}
      </Command.Item>
    )
  }

  return (
    <Command
      label="Switch project"
      shouldFilter={false}
      value={highlighted ? valueOf(highlighted) : ''}
      onValueChange={setHighlight}
      onKeyDown={onKeyDown}
      loop
    >
      <div className="flex items-center border-b px-3">
        <Search className="mr-2 size-4 shrink-0 text-muted-foreground" />
        <Command.Input
          value={query}
          onValueChange={search}
          placeholder="Find a show or venue"
          className="flex h-10 w-full bg-transparent text-sm outline-none placeholder:text-muted-foreground"
        />
      </div>
      <Command.List className="max-h-[min(360px,60vh)] overflow-y-auto p-1">
        <Command.Empty className="py-6 text-center text-sm text-muted-foreground">No show or venue matches.</Command.Empty>
        {groups.onRig && (
          <Command.Group heading="On the rig" className={GROUP_CLASS}>
            {row(groups.onRig)}
          </Command.Group>
        )}
        {groups.shows.length > 0 && (
          <Command.Group heading="Shows" className={GROUP_CLASS}>
            {groups.shows.map(row)}
          </Command.Group>
        )}
        {groups.venues.length > 0 && (
          <Command.Group heading="Venues" className={GROUP_CLASS}>
            {groups.venues.map(row)}
          </Command.Group>
        )}
      </Command.List>
      <div className="flex items-center gap-1 border-t p-1">
        <FooterButton label="All projects…" onClick={onAllProjects} />
        <span className="flex-1" />
        {isAdmin && <FooterButton label="New venue…" onClick={() => onCreate('VENUE')} />}
        <FooterButton label="New show…" onClick={() => onCreate('SHOW')} />
      </div>
      <p className="border-t px-3 py-1.5 text-[11px] text-muted-foreground">
        <kbd className="font-mono">↵</kbd> open · <kbd className="font-mono">⌥↵</kbd> make current
      </p>
    </Command>
  )
}

const GROUP_CLASS =
  '[&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:pt-2 [&_[cmdk-group-heading]]:pb-1 [&_[cmdk-group-heading]]:text-[9px] [&_[cmdk-group-heading]]:font-bold [&_[cmdk-group-heading]]:uppercase [&_[cmdk-group-heading]]:tracking-[0.09em] [&_[cmdk-group-heading]]:text-muted-foreground'

/**
 * cmdk's root takes Enter for the highlighted row whatever has focus, so a focused button keeps its
 * own Enter and Space (and ⌥Enter) by stopping them before they reach the root.
 */
function keepOwnKeys(e: KeyboardEvent) {
  if (e.key === 'Enter' || e.key === ' ') e.stopPropagation()
}

function RowButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      // The row's own press is Open; a button inside it must not also select the row.
      onClick={(e) => {
        e.stopPropagation()
        onClick()
      }}
      onPointerDown={(e) => e.stopPropagation()}
      onKeyDown={keepOwnKeys}
      className="shrink-0 rounded px-1.5 py-0.5 text-xs text-muted-foreground hover:bg-background hover:text-foreground"
    >
      {label}
    </button>
  )
}

function FooterButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} onKeyDown={keepOwnKeys} className="rounded px-2 py-1 text-xs hover:bg-accent">
      {label}
    </button>
  )
}
