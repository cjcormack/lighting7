/**
 * Manufacturer > model > mode hierarchy over the fixture types the desk knows about.
 *
 * Nothing here is Look- or preset-shaped, which is why it lives in its own module: it answers
 * "which fixture types exist and what are they called", and its consumers are the patch sheet
 * (`AddFixtureSheet`) and `FixtureTypePicker`. It also fed the Look editor's `editorFixtureType`
 * hint until session 3 deleted both. It used to
 * sit in the FX-preset DTO module, which made it look like preset machinery it never was.
 */
import type { PropertyDescriptor } from '@/store/fixtures'

export interface FixtureTypeMode {
  typeKey: string
  modeName: string | null
  channelCount: number | null
  isRegistered: boolean
  capabilities: string[]
  properties: PropertyDescriptor[]
}

export interface FixtureTypeModel {
  model: string
  manufacturer: string | null
  modes: FixtureTypeMode[]
  isRegistered: boolean // true if any mode is registered
}

export interface FixtureTypeHierarchy {
  manufacturers: Map<string, FixtureTypeModel[]> // manufacturer -> models
  models: FixtureTypeModel[] // all models flat
  typeKeyToModel: Map<string, { manufacturer: string | null; model: string; mode: FixtureTypeMode }> // typeKey -> info
}

/** Input shape accepted by the hierarchy builder — works with both fixture list and fixture types API. */
interface FixtureTypeInput {
  typeKey: string
  manufacturer?: string | null
  model?: string | null
  modeName?: string | null
  channelCount?: number | null
  isRegistered?: boolean
  capabilities?: string[]
  properties?: PropertyDescriptor[]
  // Also accept the fixture list shape (mode as nested object)
  mode?: { modeName: string; channelCount: number } | null
}

/** Build a manufacturer > model > mode hierarchy from fixture type data. */
export function buildFixtureTypeHierarchy(
  fixtures: FixtureTypeInput[],
): FixtureTypeHierarchy {
  // Group by model identity (manufacturer + model)
  const modelMap = new Map<string, FixtureTypeModel>()
  const typeKeyToModel = new Map<string, { manufacturer: string | null; model: string; mode: FixtureTypeMode }>()

  for (const f of fixtures) {
    const manufacturer = f.manufacturer || null
    const model = f.model || f.typeKey
    const modelKey = `${manufacturer ?? ''}|||${model}`
    const isRegistered = f.isRegistered ?? true
    const mode: FixtureTypeMode = {
      typeKey: f.typeKey,
      modeName: f.modeName ?? f.mode?.modeName ?? null,
      channelCount: f.channelCount ?? f.mode?.channelCount ?? null,
      isRegistered,
      capabilities: f.capabilities ?? [],
      properties: f.properties ?? [],
    }

    if (!typeKeyToModel.has(f.typeKey)) {
      typeKeyToModel.set(f.typeKey, { manufacturer, model, mode })
    }

    let entry = modelMap.get(modelKey)
    if (!entry) {
      entry = { model, manufacturer, modes: [], isRegistered: false }
      modelMap.set(modelKey, entry)
    }
    // Avoid duplicate modes (same typeKey)
    if (!entry.modes.some((m) => m.typeKey === f.typeKey)) {
      entry.modes.push(mode)
    }
    if (isRegistered) {
      entry.isRegistered = true
    }
  }

  // Group by manufacturer
  const manufacturers = new Map<string, FixtureTypeModel[]>()
  const allModels: FixtureTypeModel[] = []
  for (const entry of modelMap.values()) {
    allModels.push(entry)
    const mfr = entry.manufacturer ?? ''
    let list = manufacturers.get(mfr)
    if (!list) {
      list = []
      manufacturers.set(mfr, list)
    }
    list.push(entry)
  }

  // Sort: registered first, then alphabetical by model
  const sortModels = (a: FixtureTypeModel, b: FixtureTypeModel) => {
    if (a.isRegistered !== b.isRegistered) return a.isRegistered ? -1 : 1
    return a.model.localeCompare(b.model)
  }
  for (const list of manufacturers.values()) {
    list.sort(sortModels)
  }
  allModels.sort(sortModels)

  return { manufacturers, models: allModels, typeKeyToModel }
}
