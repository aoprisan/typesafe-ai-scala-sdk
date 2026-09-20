package typesafe.catseffect

import cats.effect.kernel.{Async, Resource, Sync}
import java.util.concurrent.{CancellationException, CompletableFuture, CompletionException, ExecutionException}
import typesafe.*

/** The [[typesafe.TypeSafeClient]] in a Cats Effect program.
  *
  * Every call is a description, not a running request: nothing leaves the JVM until the `F` is run,
  * and cancelling the fiber cancels the HTTP exchange. Failures are the SDK's own
  * [[typesafe.TypeSafeException]] types, never a `CompletionException` wrapper.
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
final class TypeSafeClientF[F[_]] private (val underlying: TypeSafeClient)(using F: Async[F]):

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
    fromFuture(underlying.systemOneAsync(state, questions, options))

  object models:
    /** The models available to this API key. */
    def list(options: CallOptions = CallOptions.default): F[ListModelsResponse] =
      fromFuture(underlying.models.listAsync(options))

  /** Release the underlying HTTP client. [[TypeSafeClientF.resource]] does this for you. */
  def close: F[Unit] = F.delay(underlying.close())

  /** Suspends the call, unwraps the JDK's exception wrappers and cancels the exchange on cancellation. */
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
    Resource.make(Sync[F].delay(new TypeSafeClientF[F](TypeSafeClient(config))))(_.close)

  /** Shorthand for an explicit key with everything else defaulted. */
  def withApiKey[F[_]: Async](apiKey: String): Resource[F, TypeSafeClientF[F]] =
    resource[F](ClientConfig(apiKey = Some(apiKey)))

  /** Lifts a client you already own; closing it stays your job. */
  def fromClient[F[_]: Async](client: TypeSafeClient): TypeSafeClientF[F] =
    new TypeSafeClientF[F](client)
