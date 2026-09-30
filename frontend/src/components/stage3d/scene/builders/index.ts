import type { StageElementDto } from '../../../../api/stageElementApi'
import { EMPTY_BUILD, isElementShown, type BuildContext, type ElementBuild } from '../sceneParts'
import { buildDrape } from './drape'
import { buildFlat } from './flat'
import { buildObject } from './object'
import { buildPlatform } from './platform'
import { buildProscenium } from './proscenium'
import { buildRoom } from './room'
import { buildSeating } from './seating'

/**
 * The element as parts, by its kind — one builder per kind (stage-view plan session 3). An element
 * that is `hidden`, or whose `visible` state is false, builds nothing; so does a kind this build
 * does not know, which a later desk may send.
 */
export function buildElement(element: StageElementDto, context: BuildContext): ElementBuild {
  if (!isElementShown(element)) return EMPTY_BUILD
  switch (element.kind) {
    case 'ROOM':
      return buildRoom(element)
    case 'PROSCENIUM':
      return buildProscenium(element)
    case 'FLAT':
      return buildFlat(element)
    case 'DRAPE':
      return buildDrape(element)
    case 'PLATFORM':
      return buildPlatform(element, context)
    case 'SEATING':
      return buildSeating(element)
    case 'OBJECT':
      return buildObject(element)
    default:
      return EMPTY_BUILD
  }
}
