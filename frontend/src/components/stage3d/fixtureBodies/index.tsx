import type { FixtureKind } from '../../../store/fixtures'
import { FresnelBody } from './FresnelBody'
import { GenericBody } from './GenericBody'
import { LaserBody } from './LaserBody'
import { MovingHeadBody } from './MovingHeadBody'
import { ParBody } from './ParBody'
import { ProfileBody } from './ProfileBody'
import { StripBody } from './StripBody'
import type { FixtureBodyProps } from './types'
import { WashBody } from './WashBody'

interface DispatchProps extends FixtureBodyProps {
  kind: FixtureKind
}

// Pick the per-kind body component. Kinds that don't have a dedicated body
// (SCANNER / BLINDER / EFFECT) fall through to GenericBody — the existing
// look — until distinct geometry is worth the extra files.
export function FixtureBody({ kind, ...rest }: DispatchProps) {
  switch (kind) {
    case 'MOVING_HEAD':
      return <MovingHeadBody {...rest} />
    case 'PROFILE':
      return <ProfileBody {...rest} />
    case 'FRESNEL':
      return <FresnelBody {...rest} />
    case 'PAR':
      return <ParBody {...rest} />
    case 'WASH':
      return <WashBody {...rest} />
    case 'STRIP':
      return <StripBody {...rest} />
    case 'LASER':
      return <LaserBody {...rest} />
    case 'SCANNER':
    case 'BLINDER':
    case 'EFFECT':
    case 'GENERIC':
    default:
      return <GenericBody {...rest} />
  }
}
