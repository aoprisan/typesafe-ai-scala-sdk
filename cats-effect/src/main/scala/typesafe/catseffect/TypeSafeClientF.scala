package typesafe.catseffect

import cats.effect.kernel.{Async, Resource, Sync}
import cats.effect.std.Random
import cats.syntax.all.*
import java.util.concurrent.{CancellationException, CompletableFuture, CompletionException, ExecutionException}
import scala.concurrent.duration.FiniteDuration
import typesafe.*

/** The [[typesafe.TypeSafeClient]] in a Cats Effect program.
  *
  * Every call is a description, not a running request: nothing leaves the JVM until the `F` is run,
  * and cancelling the fiber cancels the HTTP exchange. Failures are the SDK's own
  * [[typesafe.TypeSafeException]] types, never a `CompletionException` wrapper — and because that
  * hierarchy is sealed, [[systemOneEither]] can hand them back in a channel the compiler checks.
  *
  * The retry loop is this module's own ([[Retry]]), so attempts are timed and spaced on `F`'s clock
  * and scheduler. Only a single attempt is borrowed from the core client; what to send, what an
  * answer means and when to retry stay shared with it.
  *
  * {{{
  * import cats.effect.{IO, IOApp}
  * import typesafe.*
  * import typesafe.catseffect.*
  *
  * object Main extends IOApp.Simple:
  *   val urgent = Noul("The message conveys urgency").named("is_urgent")
  *
  *   def run = TypeSafeClientF.resource[IO]().use { client =>
  *     client.systemOne("My payouts have failed for 3 days!", Questions.of(urgent))
  *       .map(_(urgent).noul)
  *       .flatMap(IO.println)
  *   }
  * }}}
  */
final class TypeSafeClientF[F[_]] private (
    val underlying: TypeSafeClient,
    random: Option[Random[F]],
    onRetry: Option[OnRetry[F]]
)(using F: Async[F]):

  override def toString: String = s"TypeSafeClientF(${underlying.baseUrl}, model=${underlying.defaultModel})"

  /** The base URL this client talks to. */
  def baseUrl: String = underlying.baseUrl

  /** The model used when a call does not override it. */
  def defaultModel: String = underlying.defaultModel

  /** Ask typed questions about `state`. Retries (per the policy) happen inside the returned `F`. */
  def systemOne[S: ToJson](
      state: S,
      questions: Questions,
      options: CallOptions = CallOptions.default
  ): F[SystemOneResponse] =
    run(F.delay(underlying.systemOneCall(state, questions, options)))(underlying.decodeSystemOne)

  /** As [[systemOne]], but the SDK's own failures come back in the value instead of the error
    * channel — the nearest a `cats.effect` program gets to a typed error.
    *
    * [[typesafe.TypeSafeException]] is sealed, so matching on the `Left` is exhaustive and the
    * compiler will tell you when a case is missing. Anything that is *not* an SDK failure (a bug in
    * a `ToJson` instance, an `OutOfMemoryError`) stays in the error channel where it belongs, and
    * cancellation is still cancellation rather than a `Left`.
    *
    * {{{
    * client.systemOneEither(state, questions).flatMap {
    *   case Right(res)                => IO.println(res(urgent).noul)
    *   case Left(e: ApiException)     => IO.println(s"api said ${e.status}")
    *   case Left(e: TimeoutException) => IO.println(s"gave up after ${e.timeout}")
    *   case Left(e)                   => IO.raiseError(e)
    * }
    * }}}
    */
  def systemOneEither[S: ToJson](
      state: S,
      questions: Questions,
      options: CallOptions = CallOptions.default
  ): F[Either[TypeSafeException, SystemOneResponse]] =
    narrow(systemOne(state, questions, options))

  object models:
    /** The models available to this API key. */
    def list(options: CallOptions = CallOptions.default): F[ListModelsResponse] =
      run(F.delay(underlying.modelsCall(options)))(underlying.decodeModels)

    /** As [[list]], with the SDK's failures in the value. See [[systemOneEither]]. */
    def listEither(options: CallOptions = CallOptions.default): F[Either[TypeSafeException, ListModelsResponse]] =
      narrow(list(options))

  /** A copy that reports every retry to `f`.
    *
    * The SDK carries no logging or metrics dependency, so this is where log4cats, otel4s or a bare
    * counter goes. The observer cannot change the outcome: its result is discarded and its failure
    * is swallowed, because a broken counter should not fail a request that was about to succeed.
    *
    * {{{
    * TypeSafeClientF.resource[IO]().map(_.withOnRetry { ev =>
    *   logger.warn(s"${ev.endpoint} attempt ${ev.attempt} failed, waiting ${ev.delay}", ev.error)
    * })
    * }}}
    */
  def withOnRetry(f: OnRetry[F]): TypeSafeClientF[F] =
    new TypeSafeClientF[F](underlying, random, Some(f))

  /** A copy that draws its backoff jitter from `r` instead of the ambient `ThreadLocalRandom`.
    *
    * Seed it and the schedule becomes reproducible, which — with `TestControl` for the waiting —
    * makes a jittered retry assertable to the millisecond rather than to a range.
    *
    * {{{
    * Random.scalaUtilRandomSeedInt[IO](42).map(client.withRandom)
    * }}}
    */
  def withRandom(r: Random[F]): TypeSafeClientF[F] =
    new TypeSafeClientF[F](underlying, Some(r), onRetry)

  /** Release the underlying HTTP client. [[TypeSafeClientF.resource]] does this for you. */
  def close: F[Unit] = F.delay(underlying.close())

  /** The backoff's randomness: whatever [[withRandom]] was given, else the ambient thread-local. */
  private val jitter: F[Double] =
    random.getOrElse(Random.javaUtilConcurrentThreadLocalRandom[F]).nextDouble

  /** One call: prepare it, run the attempts, decode what comes back. */
  private def run[A](prepare: F[PreparedCall])(decode: (Json, ResponseMeta, String) => A): F[A] =
    prepare.flatMap { call =>
      val attempt = (n: Int) => fromFuture(underlying.sendOnce(call, n, boundAttempt = false))
      val observe = onRetry.map { f => (n: Int, e: Throwable, d: FiniteDuration) =>
        f(RetryEvent(call.endpoint, n, d, e))
      }
      Retry(call.policy, call.attemptTimeout, jitter, observe)(attempt).flatMap { (raw, meta, endpoint) =>
        F.delay(decode(raw, meta, endpoint))
      }
    }

  /** Moves the SDK's own failures into the value, and leaves every other one alone. */
  private def narrow[A](fa: F[A]): F[Either[TypeSafeException, A]] =
    fa.attempt.flatMap {
      case Right(a)                   => F.pure(Right(a))
      case Left(e: TypeSafeException) => F.pure(Left(e))
      case Left(other)                => F.raiseError(other)
    }

  /** Suspends one attempt, unwraps the JDK's exception wrappers and cancels the exchange on cancellation. */
  private def fromFuture[A](start: => CompletableFuture[A]): F[A] =
    F.async[A] { cb =>
      F.delay {
        val running = start
        running.whenComplete { (a: A, err: Throwable) =>
          if err == null then cb(Right(a)) else cb(Left(unwrap(err)))
        }
        Some(F.void(F.delay(running.cancel(true))))
      }
    }

  private def unwrap(t: Throwable): Throwable = t match
    case e: CompletionException if e.getCause != null => unwrap(e.getCause)
    case e: ExecutionException if e.getCause != null  => unwrap(e.getCause)
    case e: CancellationException                     => e
    case other                                        => other

object TypeSafeClientF:

  /** A client whose HTTP resources are released when the `Resource` is finalised. */
  def resource[F[_]: Async](config: ClientConfig = ClientConfig()): Resource[F, TypeSafeClientF[F]] =
    Resource.make(Sync[F].delay(new TypeSafeClientF[F](TypeSafeClient(config), None, None)))(_.close)

  /** Shorthand for an explicit key with everything else defaulted. */
  def withApiKey[F[_]: Async](apiKey: String): Resource[F, TypeSafeClientF[F]] =
    resource[F](ClientConfig(apiKey = Some(apiKey)))

  /** Lifts a client you already own; closing it stays your job. */
  def fromClient[F[_]: Async](client: TypeSafeClient): TypeSafeClientF[F] =
    new TypeSafeClientF[F](client, None, None)
