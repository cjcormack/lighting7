import { useMemo } from 'react'
import { Loader2 } from 'lucide-react'
import { useVisibleFixtureListQuery, type Fixture } from '../store/fixtures'
import { CompactFixtureCard, MultiElementCompactCard } from './groups/CompactFixtureCard'
import { CollapsiblePanel } from './CollapsiblePanel'

interface FixtureOverviewPanelProps {
  onFixtureClick: (fixtureKey: string) => void
  isVisible: boolean
}

export function FixtureOverviewPanel({ onFixtureClick, isVisible }: FixtureOverviewPanelProps) {
  return (
    <CollapsiblePanel isVisible={isVisible}>
      <FixtureOverviewPanelBody onFixtureClick={onFixtureClick} />
    </CollapsiblePanel>
  )
}

/** Below the collapse boundary: every card here subscribes to its own fixture's live channels. */
function FixtureOverviewPanelBody({
  onFixtureClick,
}: Pick<FixtureOverviewPanelProps, 'onFixtureClick'>) {
  const { data: fixtures, isLoading } = useVisibleFixtureListQuery()

  // Separate fixtures into single fixtures and multi-head fixtures
  const { singleFixtures, multiHeadFixtures } = useMemo(() => {
    if (!fixtures) {
      return { singleFixtures: [], multiHeadFixtures: [] }
    }

    // Filter out element fixtures (they have .element- in their key)
    const topLevel = fixtures.filter((f) => !f.key.includes('.element-'))

    const single: Fixture[] = []
    const multiHead: Fixture[] = []

    for (const fixture of topLevel) {
      if (fixture.elements && fixture.elements.length > 0) {
        multiHead.push(fixture)
      } else {
        single.push(fixture)
      }
    }

    return { singleFixtures: single, multiHeadFixtures: multiHead }
  }, [fixtures])

  const hasFixtures = singleFixtures.length > 0 || multiHeadFixtures.length > 0

  return (
    <div className="border-b bg-background px-4 py-3">
      {isLoading ? (
        <div className="flex justify-center">
          <Loader2 className="size-4 animate-spin" />
        </div>
      ) : !hasFixtures ? (
        <p className="text-sm text-muted-foreground text-center">No fixtures configured</p>
      ) : (
        <div className="flex flex-wrap gap-2">
          {/* Single fixtures */}
          {singleFixtures.map((fixture) => (
            <CompactFixtureCard
              key={fixture.key}
              fixtureKey={fixture.key}
              fixtureName={fixture.name}
              tags={[]}
              fixture={fixture}
              onClick={() => onFixtureClick(fixture.key)}
            />
          ))}

          {/* Multi-head fixtures */}
          {multiHeadFixtures.map((fixture) => (
            <MultiElementCompactCard
              key={fixture.key}
              parentKey={fixture.key}
              elementCount={fixture.elements!.length}
              fixture={fixture}
              onClick={() => onFixtureClick(fixture.key)}
            />
          ))}
        </div>
      )}
    </div>
  )
}
