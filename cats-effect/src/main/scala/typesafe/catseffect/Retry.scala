package typesafe.catseffect

import cats.effect.kernel.Temporal
import cats.syntax.all.*
import scala.concurrent.duration.FiniteDuration
import typesafe.{RetryPolicy, TimeoutException}

/** The SDK's retry semantics, run on the effect system's own clock and scheduler.
  *
  * What to retry and how long to wait stays in [[typesafe.RetryPolicy]], shared with the core
  * client, so the two loops cannot drift apart. Everything effectful — timing an attempt, sleeping
  * between them, giving up — is expressed in `F`, which makes the wait cancellable, schedules it on
  * the caller's runtime rather than a JDK timer thread, and lets tests drive it on virtual time.
  */
private[catseffect] object Retry:

  /** Runs `attempt(n)` (1-based) until it succeeds or the policy gives up.
    *
    * `jitter` supplies the randomness the backoff formula wants; a test can make it constant.
    */
  def apply[F[_], A](policy: RetryPolicy, attemptTimeout: FiniteDuration, jitter: F[Double])(
      attempt: Int => F[A]
  )(using F: Temporal[F]): F[A] =
    F.monotonic.flatMap { started =>
      def go(n: Int): F[A] =
        F.timeoutTo(attempt(n), attemptTimeout, F.raiseError[A](TimeoutException(attemptTimeout)))
          .handleErrorWith { e =>
            if !policy.isRetryable(e) then F.raiseError[A](e)
            else
              for
                random <- jitter
                now    <- F.monotonic
                delay   = policy.delay(n, e, random)
                result <- if policy.shouldStop(n, now - started, delay) then F.raiseError[A](e)
                          else F.sleep(delay) *> go(n + 1)
              yield result
          }
      go(1)
    }
