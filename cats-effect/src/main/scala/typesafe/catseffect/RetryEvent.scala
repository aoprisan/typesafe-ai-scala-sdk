package typesafe.catseffect

import scala.concurrent.duration.FiniteDuration

/** One retry, as the client reports it to an observer.
  *
  * `attempt` is the 1-based number of the attempt that just failed, `delay` the wait the policy
  * chose before the next one, and `error` what went wrong. An event is only ever reported when a
  * further attempt really is coming: the failure that exhausts the policy is raised, not announced.
  *
  * @param endpoint the call that failed, method and URL, e.g.
  *                 `POST https://api.typesafe.ai/v1/systemone` (credentials and query stripped)
  */
final case class RetryEvent(
    endpoint: String,
    attempt: Int,
    delay: FiniteDuration,
    error: Throwable
)

/** What [[TypeSafeClientF.withOnRetry]] takes: somewhere to send a [[RetryEvent]].
  *
  * The SDK deliberately has no logging or metrics dependency of its own, so this is the seam for
  * log4cats, otel4s or a plain counter. It is for watching, not for deciding — the client ignores
  * both its result and its failure.
  */
type OnRetry[F[_]] = RetryEvent => F[Unit]
