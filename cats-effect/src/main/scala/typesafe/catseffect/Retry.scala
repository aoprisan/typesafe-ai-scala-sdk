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
    * `onRetry`, when given, is told about each wait just before it starts.
    */
  def apply[F[_], A](
      policy: RetryPolicy,
      attemptTimeout: FiniteDuration,
      jitter: F[Double],
      onRetry: Option[(Int, Throwable, FiniteDuration) => F[Unit]] = None
  )(
      attempt: Int => F[A]
  )(using F: Temporal[F]): F[A] =
    // An observer watches; it does not get a vote. Its failure must not sink the call it is
    // reporting on, and it only ever runs when a further attempt really is coming.
    def announce(n: Int, e: Throwable, delay: FiniteDuration): F[Unit] =
      onRetry.fold(F.unit)(f => f(n, e, delay).handleError(_ => ()))

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
                          else announce(n, e, delay) *> F.sleep(delay) *> go(n + 1)
              yield result
          }
      go(1)
    }
