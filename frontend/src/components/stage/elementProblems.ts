/**
 * The desk's refusal of a scene element, laid out beside the form's fields (stage-view plan
 * session 5). A 400 from `stage-elements` carries every problem `validateStageElement` found —
 * the check `set_scene` makes — joined with `"; "`, each naming the field it is about the way the
 * REST body spells it: `widthM must be…`, `params.openingWidthM (9) is wider…`,
 * `params.openings[1] runs past…`, `params.states.open is a drawn drape's…`. That spelling is the
 * path the form files a problem under; a message with no path of its own (`params: railHeightM and
 * railEdge go together`) is filed under the first field it names, and one naming none is the
 * form's, drawn at the top.
 */

/** The top-level fields a problem can lead with, as the REST body spells them. */
const TOP_FIELDS = new Set([
  'name',
  'kind',
  'layer',
  'positionX',
  'positionY',
  'positionZ',
  'yawDeg',
  'widthM',
  'depthM',
  'heightM',
  'finishColour',
  'finishPattern',
  'emissive',
  'hidden',
])

/** The params keys a message without a path of its own may name, in the order they are looked for. */
const PARAM_FIELDS = [
  'openingWidthM',
  'openingHeightM',
  'openingSillM',
  'surroundM',
  'railHeightM',
  'railEdge',
  'regionUuid',
  'rows',
  'seatsPerRow',
  'rowPitchM',
  'seatPitchM',
  'firstRow',
  'rakeM',
  'role',
  'operation',
  'shape',
  'flies',
  'omit',
]

/** One problem, and the field path it belongs to (null: the form's own). */
export interface ElementProblem {
  path: string | null
  message: string
}

/**
 * A problem as it reads beside its own field: the path it leads with is the field it sits under,
 * so it goes — `widthM must be between…` reads `Must be between…` under Width. A message that only
 * names its field somewhere inside keeps every word.
 */
export function besideField(message: string, path: string): string {
  if (!message.startsWith(path)) return message
  const rest = message.slice(path.length).replace(/^[\s:]+/, '')
  return rest.length === 0 ? message : rest.charAt(0).toUpperCase() + rest.slice(1)
}

/** The problems in a refusal's message, one per `"; "`. */
export function splitProblems(error: string): string[] {
  return error
    .split('; ')
    .map((p) => p.trim())
    .filter((p) => p.length > 0)
}

/** The field path a problem is about, or null when it names none. */
export function problemPath(message: string): string | null {
  const params = /^params((?:\.[A-Za-z]+(?:\[\d+\])?)*)/.exec(message)
  if (params) {
    if (params[1]) return `params${params[1]}`
    for (const key of PARAM_FIELDS) {
      if (new RegExp(`\\b${key}\\b`).test(message)) return `params.${key}`
    }
    return null
  }
  const top = /^([A-Za-z]+)\b/.exec(message)
  if (top && TOP_FIELDS.has(top[1])) return top[1]
  return null
}

/** A refusal's message as problems filed by path. */
export function elementProblems(error: string): ElementProblem[] {
  return splitProblems(error).map((message) => ({ path: problemPath(message), message }))
}

/**
 * The problems a form draws: [at] answers a field's own, and [general] the rest — every problem
 * whose path is not one of the [shown] paths, so none is lost for want of a field to sit beside.
 */
export function fileProblems(problems: readonly ElementProblem[], shown: ReadonlySet<string>) {
  const at = (path: string) => problems.filter((p) => p.path === path).map((p) => besideField(p.message, path))
  const general = problems.filter((p) => p.path == null || !shown.has(p.path)).map((p) => p.message)
  return { at, general }
}
