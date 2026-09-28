import {InternalApiConnection} from "./internalApi";
import {Subscription} from "./subscription";

export interface FixturesApi {
  subscribe(fn: () => void): Subscription,
}

type FixturesChangedInMessage = {
  type: 'fixturesChanged',
}

export function createFixtureApi(conn: InternalApiConnection): FixturesApi {
  let nextSubscriptionId = 1
  const subscriptions = new Map<number, () => void>()

  const notifyChange = () => {
    subscriptions.forEach((fn) => {
      fn()
    })
  }

  const handleOnMessage = (message: FixturesChangedInMessage | null) => {
    if (message == null || message.type != 'fixturesChanged') {
      return
    }

    notifyChange()
  }

  conn.subscribe((evType, _ev, message) => {
    if (evType === 'message') {
      handleOnMessage(message as FixturesChangedInMessage | null)
    }
  })

  return {
    subscribe(fn: () => void): Subscription {
      const thisId = nextSubscriptionId
      nextSubscriptionId++

      subscriptions.set(thisId, fn)

      return {
        unsubscribe: () => {
          subscriptions.delete(thisId)
        },
      }
    },
  }
}
