import { resolveCell } from './columns'
import { filterTerms, fixtureMatchesTerms } from '../../lib/fixtureSearch'
import type { CellResolution, ColumnKey } from './columns'
import type { ElementDescriptor, Fixture, PropertyDescriptor } from '../../store/fixtures'
import type { GroupSummary } from '../../api/groupsApi'
import type { LocateTarget } from '../../store/locate'
import { targetKey } from '../../lib/targetKey'
import { ATTRIBUTE_FAMILIES, familyForCategory, type AttributeFamily } from '../../lib/attributeFamily'
import { EMITTER_PROPERTIES, templatePropertyFor, type TemplateProperty } from '../../lib/templateIntent'
import { spreadPropertiesFor } from '../../lib/spreadIntent'
import { degreesToDmx } from '../../lib/axisDegrees'

/**
 * Stable row identity: `group:` and `fixture:` rows are top-level; `member:`
 * rows are a fixture rendered *inside* an expanded group, prefixed with the
 * group name so a fixture in two groups yields two distinct row ids.
 */
export type RowId = string

export function groupRowId(name: string): RowId {
  return `group:${name}`
}
export function fixtureRowId(key: string): RowId {
  return `fixture:${key}`
}
export function memberRowId(group: string, key: string): RowId {
  return `member:${group}:${key}`
}
/** Element sub-row id, scoped by the PARENT ROW id (not just the fixture key)
 *  so a fixture expanded under two groups yields distinct element row ids.
 *  Ids are only ever compared, never parsed, so the embedded colons are fine. */
export function elementRowId(parentRowId: RowId, elementKey: string): RowId {
  return `element:${parentRowId}:${elementKey}`
}

export type GroupRow = {
  kind: 'group'
  id: RowId
  name: string
  /** Member fixtures that survive the active filters, in fixture-list order.
   *  Deliberately NOT the full membership: everything driven from a group row
   *  (aggregate cells, batch edits, the count badge) must only touch fixtures
   *  the operator can see — editing a group row while a filter is active must
   *  not write DMX to hidden fixtures. */
  members: Fixture[]
  isExpanded: boolean
}

export type FixtureRow = {
  kind: 'fixture'
  id: RowId
  fixture: Fixture
  /** Set on member rows (rendered indented under their group). */
  parentGroup?: string
  /** True when this fixture's element sub-rows are rendered beneath it.
   *  Always false for fixtures without elements. */
  isExpanded: boolean
}

export type ElementRow = {
  kind: 'element'
  id: RowId
  /** The owning multi-head fixture — carried explicitly; element keys are
   *  NEVER parsed (`.pixel-N` vs `.element-N` formats vary by fixture type). */
  fixture: Fixture
  element: ElementDescriptor
  /** Copied from the parent fixture row (set when rendered inside a group). */
  parentGroup?: string
}

export type DividerRow = {
  kind: 'divider'
  id: RowId
  label: string
}

export type Row = GroupRow | FixtureRow | ElementRow | DividerRow

/** Rows that can open a detail sheet (everything except dividers). */
export type InfoRow = GroupRow | FixtureRow | ElementRow

export interface BuildRowsOptions {
  fixtures: Fixture[]
  groups: GroupSummary[]
  expandedGroups: ReadonlySet<string>
  /** Fixture KEYS whose element sub-rows are expanded. Keyed by key, not row
   *  id: expanding a fixture expands every instance across groups, matching
   *  the group-name keying of `expandedGroups`. Omitted = none expanded. */
  expandedFixtures?: ReadonlySet<string>
  textFilter: string
  /** Fixture keys currently lit (dimmer > 0). `undefined` = filter off. */
  litFixtureKeys?: ReadonlySet<string>
  /** When false, emit a flat fixture list in fixture-list order — no group or
   *  member rows, no Ungrouped divider. Multi-head element expansion still
   *  applies. Default true. */
  groupByGroups?: boolean
}

/**
 * Flatten fixtures + groups into the visible row list.
 *
 * Order: groups in backend list order (each followed by its member rows when
 * expanded), then an "Ungrouped" divider and every fixture that belongs to no
 * group. Membership comes from `fixture.groups` — no per-group properties
 * fetch. A group survives the filters if any member does; when expanded, only
 * surviving members are shown.
 */
export function buildRows(opts: BuildRowsOptions): Row[] {
  const { fixtures, groups, expandedGroups, expandedFixtures, textFilter, litFixtureKeys } = opts
  const terms = filterTerms(textFilter)

  const visible = (fixture: Fixture): boolean => {
    if (!fixtureMatchesTerms(fixture, terms)) return false
    if (litFixtureKeys && !litFixtureKeys.has(fixture.key)) return false
    return true
  }

  const rows: Row[] = []

  // Fixture rows and (when expanded) their element sub-rows share one shape
  // whether the fixture renders top-level or as a group member. Element rows
  // are not individually filtered — they render only under a visible,
  // expanded parent.
  const pushFixtureRows = (fixture: Fixture, id: RowId, parentGroup?: string) => {
    const elements = fixture.elements ?? []
    const isExpanded = elements.length > 0 && (expandedFixtures?.has(fixture.key) ?? false)
    rows.push({ kind: 'fixture', id, fixture, parentGroup, isExpanded })
    if (isExpanded) {
      for (const element of elements) {
        rows.push({
          kind: 'element',
          id: elementRowId(id, element.key),
          fixture,
          element,
          parentGroup,
        })
      }
    }
  }

  if (opts.groupByGroups === false) {
    for (const fixture of fixtures) {
      if (visible(fixture)) pushFixtureRows(fixture, fixtureRowId(fixture.key))
    }
    return rows
  }

  const membersByGroup = new Map<string, Fixture[]>()
  for (const fixture of fixtures) {
    for (const groupName of fixture.groups) {
      const list = membersByGroup.get(groupName)
      if (list) list.push(fixture)
      else membersByGroup.set(groupName, [fixture])
    }
  }

  for (const group of groups) {
    const members = membersByGroup.get(group.name) ?? []
    const visibleMembers = members.filter(visible)
    // A group is hidden only when filters excluded all its members; a group
    // that is genuinely empty still gets a row (it exists, and deep-links can
    // point at it).
    if (members.length > 0 && visibleMembers.length === 0) continue
    const isExpanded = expandedGroups.has(group.name)
    rows.push({
      kind: 'group',
      id: groupRowId(group.name),
      name: group.name,
      members: visibleMembers,
      isExpanded,
    })
    if (isExpanded) {
      for (const member of visibleMembers) {
        pushFixtureRows(member, memberRowId(group.name, member.key), group.name)
      }
    }
  }

  // "Ungrouped" means no *rendered* group claims the fixture — not
  // `fixture.groups.length === 0`. If the groups query fails (or membership
  // names a group missing from the list), the fixture must still appear here
  // rather than vanish from the table entirely.
  const knownGroups = new Set(groups.map((g) => g.name))
  const ungrouped = fixtures.filter(
    (f) => !f.groups.some((name) => knownGroups.has(name)) && visible(f),
  )
  if (ungrouped.length > 0) {
    if (rows.length > 0) {
      rows.push({ kind: 'divider', id: 'divider:ungrouped', label: 'Ungrouped' })
    }
    for (const fixture of ungrouped) {
      pushFixtureRows(fixture, fixtureRowId(fixture.key))
    }
  }

  return rows
}

// === Batch apply ===

export type CellCommit =
  | { kind: 'slider'; value: number }
  | { kind: 'colour'; r: number; g: number; b: number; w?: number; a?: number; uv?: number }
  /** Position commits are per-axis: an omitted axis is left untouched, so a
   *  pan nudge on a batch selection doesn't overwrite every target's tilt
   *  with one row's aggregate.
   *
   *  An axis is said **either** as a byte (`pan`) **or** in travel degrees
   *  (`panDeg`), never both. Degrees are the position editor's unit on a head
   *  that annotates its range (editor-kit plan D14), and they are resolved to
   *  each target's own byte in `clampCommitToResolution` — the one place with
   *  the target descriptor in hand — because 270° is byte 128 on a 540° mover
   *  and byte 109 on a 630° one. A target whose axis carries no annotation
   *  cannot take a degree, and that axis is left untouched on it. */
  | { kind: 'position'; pan?: number; tilt?: number; panDeg?: number; tiltDeg?: number }
  | { kind: 'setting'; level: number }

/**
 * Anything a batch write can address: a whole fixture, or one element of a
 * multi-head fixture. `Fixture` and `ElementDescriptor` are both structurally
 * assignable — no adapters at call sites. `key` is what cue-mode writes use
 * as their `targetKey` (element keys are valid there).
 */
export interface WriteTarget {
  key: string
  properties: PropertyDescriptor[]
  /** Present on fixture targets. When a column resolves to nothing against
   *  `properties`, planBatchWrites falls through to one write per element. */
  elements?: ElementDescriptor[]
  /**
   * Present on **element** targets: the owning fixture's key. Element keys are never parsed
   * (`.pixel-N` vs `.element-N` varies by fixture type), so this is the one way a reader that has
   * only the target — the colour editor, mounting one appearance leaf per parent for Pick — gets
   * back to the head it belongs to. Absent on a fixture target, whose `key` is its own.
   */
  fixtureKey?: string
  /**
   * With [fixtureKey]: the element's **position** in its parent's element list, which is the index
   * the parent's appearance `segments` are read by (the rig tile's rule). Position rather than
   * `ElementDescriptor.index` so a reader and the segment list cannot disagree on a fixture whose
   * indices are not dense.
   */
  cellIndex?: number
}

/** The write targets a single row stands for: a group row its members, a
 *  fixture row its fixture, an element row its element. The one place row
 *  kind is mapped to targets — single-row edits, selection expansion, and
 *  cell display all go through it. */
export function rowWriteTargets(row: Row): WriteTarget[] {
  switch (row.kind) {
    case 'group':
      return row.members
    case 'fixture':
      return [row.fixture]
    case 'element': {
      // Stamped here, the one place a row becomes targets, so every consumer of an element target
      // — the batch, the marquee's expansion, the colour editor's heads — sees the same parent.
      const cellIndex = (row.fixture.elements ?? []).findIndex((element) => element.key === row.element.key)
      return [{ ...row.element, fixtureKey: row.fixture.key, cellIndex: cellIndex < 0 ? undefined : cellIndex }]
    }
    case 'divider':
      return []
  }
}

/** The locate target a row stands for (element rows locate their own element
 *  key — the backend resolves those). Shared by the toolbar's locate list and
 *  the per-row hover button so the two can never disagree. */
export function rowLocateTarget(row: Row): LocateTarget | null {
  switch (row.kind) {
    case 'group':
      return { type: 'group', key: row.name }
    case 'fixture':
      return { type: 'fixture', key: row.fixture.key }
    case 'element':
      return { type: 'fixture', key: row.element.key }
    case 'divider':
      return null
  }
}

/**
 * Fixture keys already covered at whole-fixture level by the selection: a
 * selected fixture/member row, or membership of a selected group row.
 * Element rows under a covered parent must be dropped by every selection-wide
 * action (writes, locate) regardless of row order — ⌘A selects parent and
 * children together, and acting on both double-writes or double-toggles.
 */
export function coveredFixtureKeys(
  rows: readonly Row[],
  selectedIds: ReadonlySet<RowId>,
): Set<string> {
  const covered = new Set<string>()
  for (const row of rows) {
    if (!selectedIds.has(row.id)) continue
    if (row.kind === 'group') for (const member of row.members) covered.add(member.key)
    else if (row.kind === 'fixture') covered.add(row.fixture.key)
  }
  return covered
}

/**
 * How many distinct **fixtures** the rows in front of the operator stand for.
 *
 * Deduped by fixture key, and that is the whole point of it being a function rather than a
 * `rows.filter(r => r.kind === 'fixture').length` at the call site. `buildRows` calls
 * `pushFixtureRows` once per group membership, so a head patched into two expanded groups is two
 * `fixture` rows; counting rows would report 26 heads on a rig of 25. It sits beside
 * [selectedRowTargets], which dedupes by the same key for the same reason ("a fixture selected
 * through two group memberships is one head") — and the two are rendered *side by side* in the
 * programmer's legend footer as `N fixtures · M selected`, so one deduping and the other not is
 * how that footer comes to read "26 fixtures · 25 selected" with everything on screen selected.
 *
 * Group and element rows are not fixtures and do not count: a group is a way of naming heads, and
 * an element is one head's sub-row. Dividers are not rows of anything.
 */
export function countFixtureRows(rows: readonly Row[]): number {
  const seen = new Set<string>()
  for (const row of rows) {
    if (row.kind === 'fixture') seen.add(row.fixture.key)
  }
  return seen.size
}

/**
 * What the selected rows *are*, as targets — a group row one `group` entry, never its members.
 *
 * Three callers, and the third is why this is a function rather than a memo in one of them: the
 * locate toolbar, the per-row locate button, and the desk-selection bridge
 * (`useDeskSelectionBridge`). §3.2 of `midi-surface-plan.md` names the mistake it exists to
 * prevent — publishing the `programmer` scope's `targetKeys` instead, which
 * `expandSelectionToTargets` has already flattened to member keys carrying no group entry and no
 * discriminator. A marquee over *Front wash* would then arrive at the desk as eight loose
 * fixtures, the strip's group select LED would stay dark, and nothing would light the group row
 * again on the way back.
 *
 * Element rows under a covered parent are dropped and the result is deduped by `(type, key)`, both
 * for locate's reason: a fixture selected through two group memberships must toggle once, and
 * locating a parent already resolves its elements.
 */
export function selectedRowTargets(
  rows: readonly Row[],
  selectedIds: ReadonlySet<RowId>,
): LocateTarget[] {
  const seen = new Set<string>()
  const targets: LocateTarget[] = []
  for (const row of selectedContributingRows(rows, selectedIds)) {
    const target = rowLocateTarget(row)
    if (!target) continue
    const key = targetKey(target)
    if (seen.has(key)) continue
    seen.add(key)
    targets.push(target)
  }
  return targets
}

/**
 * Where a **template** press lands for these rows: the fixtures they stand for, as
 * `{type: 'fixture', key}` — the shape `POST /templates/{id}/apply` and `/toggle` take.
 *
 * Neither of its two siblings answers this. [selectedRowTargets] keeps a group row *as a group*,
 * which the desk then expands to every member — including the ones an active filter hides, and
 * "editing a group row while a filter is active must not write to hidden fixtures" is the rule
 * every other group-row action here keeps (`GroupRow.members`). [expandSelectionToTargets]
 * respects the filter but hands back an *element* for a lone element row, and an element key is
 * not a fixture key: the template route resolves targets against the patch and would drop it
 * silently. A template cannot address one head of a fixture anyway, so an element row lands on
 * its fixture.
 *
 * Deduped by key: a fixture selected through two group memberships is one head.
 */
export function templateTargetsFor(
  rows: readonly Row[],
  selectedIds: ReadonlySet<RowId>,
): LocateTarget[] {
  const seen = new Set<string>()
  const out: LocateTarget[] = []
  const add = (key: string) => {
    if (seen.has(key)) return
    seen.add(key)
    out.push({ type: 'fixture', key })
  }
  for (const row of selectedContributingRows(rows, selectedIds)) {
    switch (row.kind) {
      case 'group':
        for (const member of row.members) add(member.key)
        break
      case 'fixture':
      case 'element':
        add(row.fixture.key)
        break
      case 'divider':
        break
    }
  }
  return out
}

/**
 * Where a **spread** lands for these rows: the marquee's heads as the desk takes them
 * (editor-kit plan D4) — a group row expanded to its **visible** members (the filter rule every
 * group-row action keeps), a fixture row as itself, and an element row **as a cell**,
 * `{type: 'fixture', key: element.key}`, the cells contract the desk already takes
 * (`rowLocateTarget` publishes it and `BuskRigOrder` sorts it).
 *
 * Not [templateTargetsFor], deliberately: that one folds an element row into its fixture because
 * the template route resolves keys against the patch, and a spread over a bar's cells has to keep
 * the cells. It is [expandSelectionToTargets]'s walk — the same dedupe, the same drop of an element
 * row under a covered parent — read as cue targets, through [spreadTargetsOf], so the container's
 * per-column targets and this answer cannot name different heads for one marquee.
 */
export function spreadTargetsFor(
  rows: readonly Row[],
  selectedIds: ReadonlySet<RowId>,
): LocateTarget[] {
  return spreadTargetsOf(expandSelectionToTargets(rows, selectedIds))
}

/** [spreadTargetsFor] over write targets already expanded: every target by its own key, a cell by its element key. */
export function spreadTargetsOf(targets: readonly WriteTarget[]): LocateTarget[] {
  const seen = new Set<string>()
  const out: LocateTarget[] = []
  for (const target of targets) {
    if (seen.has(target.key)) continue
    seen.add(target.key)
    out.push({ type: 'fixture', key: target.key })
  }
  return out
}

/**
 * The template property a grid column spreads as — the column → `TemplateProperty` map of
 * editor-kit plan D4. Null for a column outside the vocabulary: Speed spreads as **raw** bytes
 * (D15), and Gobo and Prism do not spread at all (a wheel slot is per-model; a prism is a switch).
 */
export function spreadPropertyForColumn(col: ColumnKey): TemplateProperty | null {
  switch (col) {
    case 'dimmer':
      return templatePropertyFor('dimmer')
    case 'strobe':
      return templatePropertyFor('strobe')
    case 'colour':
      return templatePropertyFor('rgbColour')
    case 'position':
      return templatePropertyFor('position')
    case 'zoom':
      return templatePropertyFor('zoom')
    case 'focus':
      return templatePropertyFor('focus')
    case 'iris':
      return templatePropertyFor('iris')
    case 'gobo':
    case 'prism':
    case 'speed':
      return null
  }
}

/** Whether one head — a fixture or an element — has something this template property resolves against. */
function headTakesProperty(properties: readonly PropertyDescriptor[], property: TemplateProperty): boolean {
  switch (property.propertyName) {
    case 'rgbColour':
      return properties.some((p) => p.type === 'colour')
    // The bundled emitters are omitted from the flat descriptor list, so read the colour
    // descriptor's channels (`targetEmitters`'s rule).
    case 'white':
      return properties.some((p) => (p.type === 'colour' && p.whiteChannel != null) || p.category === 'white')
    case 'amber':
      return properties.some((p) => (p.type === 'colour' && p.amberChannel != null) || p.category === 'amber')
    case 'uv':
      return properties.some((p) => (p.type === 'colour' && p.uvChannel != null) || p.category === 'uv')
    case 'position':
      return resolveCell([...properties], 'position') != null
    case 'prism':
      return properties.some((p) => p.category === 'prism')
    default:
      // dimmer, strobe, zoom, focus, iris, frost: a property in that category.
      return properties.some((p) => p.category === property.propertyName)
  }
}

/**
 * The properties of [family] a spread over these targets can carry — the Property row's items
 * (D4): the template vocabulary for the family, minus the switch, kept to what **some** head in
 * the selection has. A Colour marquee over RGBW heads offers Colour · White, over RGB heads Colour
 * alone; a Dimmer marquee over heads with a strobe offers Level · Strobe. An element's properties
 * count for its fixture, as [targetFamilies] counts them.
 */
export function spreadPropertiesOffered(targets: readonly WriteTarget[], family: AttributeFamily): TemplateProperty[] {
  return spreadPropertiesFor(family).filter((property) =>
    targets.some(
      (target) =>
        headTakesProperty(target.properties, property) ||
        (target.elements ?? []).some((element) => headTakesProperty(element.properties, property)),
    ),
  )
}

/**
 * How many steps a spread **over Cells** has for these targets, and 0 where the switch has nothing
 * to split (D5): a fixture target's cells count, and a cell selected on its own is one — the busk
 * tab's `selectedCells` reading, so *Over: Cells* counts the same way from either surface. Zero
 * unless some fixture target has cells, since a selection of lone cells is already over cells.
 */
export function spreadCellCount(targets: readonly WriteTarget[]): number {
  if (!targets.some((target) => (target.elements?.length ?? 0) > 0)) return 0
  let count = 0
  for (const target of targets) {
    const cells = target.elements?.length ?? 0
    count += cells > 0 ? cells : 1
  }
  return count
}

/**
 * The attribute families these targets can take at all — what a head *has*, never what it is
 * currently showing. An element's properties count for its fixture, so a pixel bar whose colour
 * lives on its elements is a colour target.
 *
 * This is the capability half of template compatibility (fx-templates D6): a template is in exactly
 * one family, so "does the selection have anything in that family" is the whole question, and it is
 * answered from descriptors rather than from `Fixture.capabilities` because BEAM has no capability
 * string — `fixturesSupportingFamily` counts every head for it, where a descriptor set can say
 * whether a head has a wheel, a zoom, an iris.
 */
export function targetFamilies(targets: readonly WriteTarget[]): AttributeFamily[] {
  const out = new Set<AttributeFamily>()
  for (const target of targets) {
    for (const property of target.properties) out.add(familyForCategory(property.category))
    for (const element of target.elements ?? []) {
      for (const property of element.properties) out.add(familyForCategory(property.category))
    }
  }
  return ATTRIBUTE_FAMILIES.filter((family) => out.has(family))
}

/**
 * Which bundled colour emitters these targets have — the other half of template compatibility.
 *
 * A **union**, exactly like [targetFamilies] beside it: offer the template if *any* selected head
 * can serve it, and let the per-head refusal report the rest as skips. An intersection would make a
 * mixed selection offer almost nothing, which is the state an operator busks in most of the time.
 *
 * Answered from descriptors, and it has to be: `bundleWithColour` sliders are **omitted** from the
 * flat descriptor list and folded into the colour descriptor's `whiteChannel` / `amberChannel` /
 * `uvChannel` (see `hooks/useTargetProperties`), so scanning categories the way [targetFamilies]
 * does would find no emitter on any head. The `slider`-with-that-category arm is the belt to that
 * braces — it costs one comparison and catches a head that publishes an emitter as a plain slider
 * rather than folding it in. Same probe as `detectExtendedChannels` in `components/fx/colourUtils`,
 * which asks it of a whole picker rather than of a selection.
 */
export function targetEmitters(targets: readonly WriteTarget[]): string[] {
  const out = new Set<string>()
  // Narrowed on `type`, never cast. `PropertyDescriptor`'s colour arm declares the three channel
  // fields, so this type-checks for free — and an earlier version that reached them through an
  // `as Record<string, unknown>` also read a `memberColourChannels` that is not on this union at
  // all (it belongs to `GroupColourPropertyDescriptor`, which never reaches a `WriteTarget`: group
  // rows are expanded to member fixtures before they get here). The cast is what let that dead
  // branch compile.
  const scan = (properties: readonly PropertyDescriptor[]) => {
    for (const property of properties) {
      if (property.type === 'colour') {
        if (property.whiteChannel != null) out.add('white')
        if (property.amberChannel != null) out.add('amber')
        if (property.uvChannel != null) out.add('uv')
      } else if (property.type === 'slider' && EMITTER_PROPERTIES.includes(property.category)) {
        out.add(property.category)
      }
    }
  }
  for (const target of targets) {
    scan(target.properties)
    for (const element of target.elements ?? []) scan(element.properties)
  }
  return EMITTER_PROPERTIES.filter((emitter) => out.has(emitter))
}

/**
 * The selected rows that stand for something, in visible order — the subtle half of every
 * selection-wide action, factored out so it is written once.
 *
 * A row contributes when it is selected **and** is not an element row whose parent fixture is
 * already covered. That second clause is the one worth having in one place: ⌘A selects a parent and
 * its children together, so acting on both double-writes a value or cancels a locate toggle
 * outright. [selectedRowTargets] and [expandSelectionToTargets] differ only in what they extract
 * per row and how they dedupe; they used to differ in this too, by copy.
 */
function selectedContributingRows(
  rows: readonly Row[],
  selectedIds: ReadonlySet<RowId>,
): Row[] {
  const covered = coveredFixtureKeys(rows, selectedIds)
  return rows.filter(
    (row) =>
      selectedIds.has(row.id) && !(row.kind === 'element' && covered.has(row.fixture.key)),
  )
}

/**
 * [selectedRowTargets] read backwards: which rows stand for these targets.
 *
 * Every row that answers a target, not the first — a fixture in two expanded groups is two rows and
 * both are the same head, so lighting one and not the other would look like a bug. A target with no
 * row (filtered out, or a group this list is not showing) contributes nothing, which is what keeps
 * the desk's list wider than the view rather than the view narrowing the desk's.
 */
export function rowIdsForTargets(
  rows: readonly Row[],
  targets: readonly LocateTarget[],
): RowId[] {
  if (targets.length === 0) return []
  const wanted = new Set(targets.map(targetKey))
  const ids: RowId[] = []
  for (const row of rows) {
    const target = rowLocateTarget(row)
    if (target && wanted.has(targetKey(target))) ids.push(row.id)
  }
  return ids
}

/**
 * Expand a selection to the distinct write targets it covers, in visible row
 * order: group rows contribute their members (fixture-list order),
 * fixture/member rows contribute their fixture, element rows contribute their
 * element. Deduped by key, and element rows whose parent fixture is already
 * covered are dropped (see coveredFixtureKeys).
 */
export function expandSelectionToTargets(
  rows: readonly Row[],
  selectedIds: ReadonlySet<RowId>,
): WriteTarget[] {
  const seen = new Set<string>()
  const out: WriteTarget[] = []
  for (const row of selectedContributingRows(rows, selectedIds)) {
    for (const target of rowWriteTargets(row)) {
      if (seen.has(target.key)) continue
      seen.add(target.key)
      out.push(target)
    }
  }
  return out
}

/** Does this commit make sense against this resolution? A colour commit can't
 *  drive a colour *wheel* (option levels, not RGB), and vice versa. */
export function commitMatchesResolution(
  commit: CellCommit,
  res: CellResolution,
): boolean {
  if (!res) return false
  switch (commit.kind) {
    case 'slider':
      return res.kind === 'slider'
    case 'colour':
      return res.kind === 'colour'
    case 'position':
      return res.kind === 'position'
    case 'setting':
      return res.kind === 'setting' || res.kind === 'colour-setting'
  }
}

export interface PlannedWrite {
  target: WriteTarget
  resolution: NonNullable<CellResolution>
  /** The commit clamped to THIS target's descriptor ranges. */
  commit: CellCommit
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, Math.round(value)))
}

/**
 * Clamp a commit's values to one fixture's descriptor ranges. A batch commit
 * carries a single value picked against the edited row's ranges; each target
 * fixture may have a narrower (or wider) channel range — e.g. a 540° pan next
 * to a 255-step one — so the clamp has to happen per resolution, at the one
 * place that has the target descriptor in hand.
 */
export function clampCommitToResolution(
  commit: CellCommit,
  resolution: NonNullable<CellResolution>,
): CellCommit {
  if (commit.kind === 'slider' && resolution.kind === 'slider') {
    const { min, max } = resolution.property
    return { kind: 'slider', value: clamp(commit.value, min, max) }
  }
  if (commit.kind === 'position' && resolution.kind === 'position') {
    // A degree resolves through the target's own annotation, and only there: the editor only
    // sends degrees when every head in its batch annotates, so a null here is a head the editor
    // never saw — and leaving the axis alone is safer than inventing a byte for it.
    const panFromDeg =
      commit.panDeg === undefined || resolution.panProperty == null
        ? undefined
        : (degreesToDmx(commit.panDeg, resolution.panProperty) ?? undefined)
    const tiltFromDeg =
      commit.tiltDeg === undefined || resolution.tiltProperty == null
        ? undefined
        : (degreesToDmx(commit.tiltDeg, resolution.tiltProperty) ?? undefined)
    const pan = commit.pan ?? panFromDeg
    const tilt = commit.tilt ?? tiltFromDeg
    return {
      kind: 'position',
      pan: pan === undefined ? undefined : clamp(pan, resolution.panMin, resolution.panMax),
      tilt: tilt === undefined ? undefined : clamp(tilt, resolution.tiltMin, resolution.tiltMax),
    }
  }
  return commit
}

/**
 * What one cell editor's commit will land on — the marquee's heads for a cell inside it, the row's
 * own targets otherwise — as the editor needs to read it (editor-kit plan D8, D13, D14).
 *
 * [count] is resolutions, not rows: a collapsed 12-head bar's colour cell lands on twelve, which is
 * what `planBatchWrites` expands the commit into. [skipped] is the targets that resolve nothing for
 * the column — a marquee is geometric and sweeps the par's empty Gobo cell along with the spot's —
 * and is what the read-out says (*2 heads have no gobo · skipped*). [resolutions] is what the
 * editor reads its ranges and annotations off: whether every head's dimmer is 0–255, whether every
 * mover annotates its pan in degrees.
 */
export interface CellBatch {
  count: number
  skipped: number
  resolutions: readonly NonNullable<CellResolution>[]
  /**
   * The targets themselves, in visible row order — the marquee's heads for this column. The colour
   * editor reads them for its emitter counts, its hidden appearance leaves and the template
   * targets its Save and Recent land on (editor-kit plan D10, D12); a count and a resolution list
   * cannot name a head. Optional only because a cell mounted with no batch (`CueValueGrid`, a
   * read-only row) builds a one-row stand-in from its own resolutions, which name no head;
   * [batchForTargets] always fills it.
   */
  targets?: readonly WriteTarget[]
}

/** One column's [CellBatch] over its targets — the container keeps one per marquee column. */
export function batchForTargets(targets: readonly WriteTarget[], col: ColumnKey): CellBatch {
  const resolutions: NonNullable<CellResolution>[] = []
  let skipped = 0
  for (const target of targets) {
    const cells = resolveTargetCells(target, col)
    if (cells.length === 0) skipped += 1
    for (const cell of cells) resolutions.push(cell.resolution)
  }
  return { count: resolutions.length, skipped, resolutions, targets }
}

/**
 * Two position commits inside one throttle window, as one: the later commit's axis wins where it
 * says one, the earlier one's is kept where it does not — so a pan tick does not discard a pending
 * tilt tick (or vice versa). **Per axis, one unit**: an axis said in degrees clears the byte the
 * pending commit carried for it, and a byte clears the degree, because `clampCommitToResolution`
 * reads the byte first and a stale byte beside a fresh degree would win. Before this existed the
 * container's merge copied `pan` / `tilt` alone and the degree arm's third commit in a window
 * became an empty write (editor-kit session 1 review, finding 1).
 */
export function mergePositionCommits(
  prev: Extract<CellCommit, { kind: 'position' }>,
  next: Extract<CellCommit, { kind: 'position' }>,
): Extract<CellCommit, { kind: 'position' }> {
  const out: Extract<CellCommit, { kind: 'position' }> = { kind: 'position' }
  if (next.pan !== undefined) out.pan = next.pan
  else if (next.panDeg !== undefined) out.panDeg = next.panDeg
  else if (prev.pan !== undefined) out.pan = prev.pan
  else if (prev.panDeg !== undefined) out.panDeg = prev.panDeg
  if (next.tilt !== undefined) out.tilt = next.tilt
  else if (next.tiltDeg !== undefined) out.tiltDeg = next.tiltDeg
  else if (prev.tilt !== undefined) out.tilt = prev.tilt
  else if (prev.tiltDeg !== undefined) out.tiltDeg = prev.tiltDeg
  return out
}

export interface TargetResolution {
  target: WriteTarget
  resolution: NonNullable<CellResolution>
}

/**
 * THE parent-first precedence rule, in one place: a target's own properties
 * claim the column outright when they resolve it (the backend's canonical
 * multi-head shape is a master dimmer/strobe on the parent with
 * colour/position on the heads); only when they resolve nothing do elements
 * contribute one resolution each, in element order. Cell display
 * (buildRowCells), batch writes (planBatchWrites), and Highlight's dimmer
 * lookup all go through this, so what a row shows is always what an edit
 * writes. Precedence keys on resolution nullness, never on commit shape — a
 * parent colour wheel claims the colour column even though a colour commit
 * against it is later skipped.
 */
export function resolveTargetCells(target: WriteTarget, col: ColumnKey): TargetResolution[] {
  const own = resolveCell(target.properties, col)
  if (own) return [{ target, resolution: own }]
  const out: TargetResolution[] = []
  for (const element of target.elements ?? []) {
    const resolution = resolveCell(element.properties, col)
    if (resolution) out.push({ target: element, resolution })
  }
  return out
}

/**
 * The single code path behind group-row edit, multi-select batch apply, and
 * single-row edit (n = 1): which targets actually take this commit on this
 * column, with their resolved descriptors and the commit clamped to each
 * target's own ranges. Targets without the property, or whose property can't
 * take the commit's shape, are skipped. Multi-head expansion and parent
 * precedence come from resolveTargetCells; element writes land inline at the
 * parent's position (the ordering the raw spread depends on).
 */
export function planBatchWrites(
  targets: readonly WriteTarget[],
  col: ColumnKey,
  commit: CellCommit,
): PlannedWrite[] {
  const out: PlannedWrite[] = []
  for (const outer of targets) {
    for (const { target, resolution } of resolveTargetCells(outer, col)) {
      if (!commitMatchesResolution(commit, resolution)) continue
      out.push({
        target,
        resolution,
        commit: clampCommitToResolution(commit, resolution),
      })
    }
  }
  return out
}

// === Deep-link select params ===
//
// The `?select=` search-param vocabulary is authored by the command palette
// and parsed by the Fixtures List route; both sides go through these helpers
// so the format lives in exactly one place.

/** What ← or → does to the anchor row. See [treeKeyAction]. */
export type TreeKeyAction =
  | { kind: 'expand'; row: GroupRow | FixtureRow }
  | { kind: 'collapse'; row: GroupRow | FixtureRow }
  | { kind: 'select-parent'; rowId: RowId }

/**
 * The tree half of the list's keyboard: → opens the anchor row, ← closes it or climbs to its parent.
 *
 * The ARIA tree convention, applied to the two kinds of row that open — a group row over its
 * members, and a multi-head fixture over its elements. → on a closed one opens it and does nothing
 * on anything else; ← on an open one closes it, and on a row that is *inside* something — a member
 * of a group, an element of a fixture — moves the selection up to the parent instead, so a second
 * ← then closes it. Null where the key means nothing here, so the caller leaves the event alone.
 *
 * A member's parent is its group row; an element's is the fixture row it was rendered under, which
 * is the member row when that fixture sits in a group (`elementRowId` scopes by the parent row for
 * the same reason). Ids are built by the same helpers `buildRows` uses, so the two cannot drift.
 */
export function treeKeyAction(row: Row, key: 'ArrowLeft' | 'ArrowRight'): TreeKeyAction | null {
  if (row.kind === 'divider') return null
  const expandable =
    row.kind === 'group' || (row.kind === 'fixture' && (row.fixture.elements?.length ?? 0) > 0)
  if (key === 'ArrowRight') {
    return expandable && !row.isExpanded ? { kind: 'expand', row } : null
  }
  if (expandable && row.isExpanded) return { kind: 'collapse', row }
  if (row.kind === 'element') {
    const parent = row.parentGroup
      ? memberRowId(row.parentGroup, row.fixture.key)
      : fixtureRowId(row.fixture.key)
    return { kind: 'select-parent', rowId: parent }
  }
  if (row.kind === 'fixture' && row.parentGroup !== undefined) {
    return { kind: 'select-parent', rowId: groupRowId(row.parentGroup) }
  }
  return null
}

export type SelectParam = { kind: 'fixture' | 'group'; key: string }

export function fixtureSelectParam(key: string): string {
  return `fixture:${key}`
}

export function groupSelectParam(name: string): string {
  return `group:${name}`
}

export function parseSelectParam(raw: string): SelectParam | null {
  const separator = raw.indexOf(':')
  if (separator === -1) return null
  const kind = raw.slice(0, separator)
  const key = raw.slice(separator + 1)
  if ((kind !== 'fixture' && kind !== 'group') || key === '') return null
  return { kind, key }
}
