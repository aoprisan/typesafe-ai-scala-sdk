package typesafe.fs2streams

import cats.effect.kernel.{Ref, Temporal}
import cats.syntax.all.*
import scala.concurrent.duration.{Duration, FiniteDuration}

/** A token bucket for outgoing calls: one token every `every`, up to `burst` of them banked.
  *
  * The state is a single instant — the theoretical arrival time of the next token — rather than a
  * token count refilled by a background fiber. Bumping it by `every` on each acquire *is* the
  * refill, so there is no timer to leak, nothing to shut down, and the whole decision is one
  * atomic `Ref.modify`, which is what makes it safe under `parEvalMap`.
  *
  * A caller that arrives early sleeps exactly long enough; one that arrives after an idle stretch
  * finds the arrival time in the past and goes straight through, up to `burst` times over.
  */
private[fs2streams] final class Throttle[F[_]] private (
    every: FiniteDuration,
    tolerance: FiniteDuration,
    nextToken: Ref[F, FiniteDuration]
)(using F: Temporal[F]):

  /** Takes a token, waiting for it if the bucket is empty.
    *
    * The token is claimed when the decision is made, not when the wait ends, so a caller cancelled
    * mid-wait still spends it. That errs towards staying under the limit, which is the side to err
    * on when the alternative is a `429`.
    */
  def acquire: F[Unit] =
    F.monotonic
      .flatMap { now =>
        nextToken.modify { tat =>
          val mine = if tat > now then tat else now // an idle bucket does not bank time forever
          val wait = mine - now - tolerance
          (mine + every, if wait > Duration.Zero then wait else Duration.Zero)
        }
      }
      .flatMap(F.sleep)

private[fs2streams] object Throttle:

  /** `every` is the steady spacing between call starts; `burst` how many may go at once. */
  def apply[F[_]](every: FiniteDuration, burst: Int)(using F: Temporal[F]): F[Throttle[F]] =
    if every <= Duration.Zero then
      F.raiseError(IllegalArgumentException(s"every must be a positive duration, got $every"))
    else if burst < 1 then F.raiseError(IllegalArgumentException(s"burst must be at least 1, got $burst"))
    else
      // `burst` tokens at once means tolerating an arrival time `burst - 1` intervals ahead of now.
      F.ref(Duration.Zero: FiniteDuration).map(new Throttle[F](every, every * (burst - 1).toLong, _))
