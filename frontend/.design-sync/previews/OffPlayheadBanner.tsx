import { OffPlayheadBanner } from 'lighting-desk-ui'

const noop = () => {}

/** Reading one stack while GO would fire another — both actions offered. */
export const Default = () => (
  <OffPlayheadBanner
    liveStackName="Act 1 — Opening"
    selectedStackName="Act 2 — Ballroom"
    liveCueIsOnStage
    onJumpToLive={noop}
    onMakeLive={noop}
  />
)

/** The show is stopped, so there is no live stack to jump back to and nothing to confirm. */
export const ShowNotRunning = () => (
  <OffPlayheadBanner
    liveStackName={null}
    selectedStackName="Pre-show"
    liveCueIsOnStage={false}
    onJumpToLive={noop}
    onMakeLive={noop}
  />
)

/** Long stack names wrap the message and push the buttons to a second line. */
export const LongNames = () => (
  <OffPlayheadBanner
    liveStackName="Act 3 — Finale, curtain call and walk-out music"
    selectedStackName="Interval — house at three-quarters with bar pre-sets"
    liveCueIsOnStage
    onJumpToLive={noop}
    onMakeLive={noop}
  />
)
