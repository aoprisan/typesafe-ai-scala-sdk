package typesafe.fs2streams

import cats.effect.kernel.Async
import fs2.{Pipe, Stream}
import typesafe.*
import typesafe.catseffect.TypeSafeClientF

/** fs2 pipes that ask the same questions of every element of a stream of states.
  *
  * The API has no streaming endpoint; what streams here is your own workload — a table, a queue, a
  * file of tickets — run through System One with a bounded number of calls in flight.
  *
  * {{{
  * import cats.effect.IO
  * import fs2.Stream
  * import typesafe.*
  * import typesafe.fs2streams.*
  *
  * val urgent = Noul("The message conveys urgency").named("is_urgent")
  *
  * TypeSafeStream.resource[IO]().flatMap { client =>
  *   Stream.emits(tickets).through(client.systemOnePipe(Questions.of(urgent), maxConcurrent = 8))
  * }.map(_(urgent).noul).compile.toVector
  * }}}
  */
extension [F[_]](client: TypeSafeClientF[F])(using F: Async[F])

  /** Answers for every state, in input order, at most `maxConcurrent` calls in flight.
    *
    * The first failure that survives the retry policy fails the stream.
    */
  def systemOnePipe[S: ToJson](
      questions: Questions,
      maxConcurrent: Int = 4,
      options: CallOptions = CallOptions.default
  ): Pipe[F, S, SystemOneResponse] =
    _.parEvalMap(maxConcurrent)(client.systemOne(_, questions, options))

  /** As [[systemOnePipe]], but answers are emitted as they arrive rather than in input order. */
  def systemOneUnorderedPipe[S: ToJson](
      questions: Questions,
      maxConcurrent: Int = 4,
      options: CallOptions = CallOptions.default
  ): Pipe[F, S, SystemOneResponse] =
    _.parEvalMapUnordered(maxConcurrent)(client.systemOne(_, questions, options))

  /** As [[systemOnePipe]], but one bad state does not sink the run: each state is emitted with its
    * outcome, so failures can be logged, counted or retried later.
    */
  def systemOneAttemptPipe[S: ToJson](
      questions: Questions,
      maxConcurrent: Int = 4,
      options: CallOptions = CallOptions.default
  ): Pipe[F, S, (S, Either[Throwable, SystemOneResponse])] =
    _.parEvalMap(maxConcurrent)(state => F.map(F.attempt(client.systemOne(state, questions, options)))(state -> _))

object TypeSafeStream:

  /** A single-element stream of a client whose HTTP resources close when the stream finishes. */
  def resource[F[_]: Async](config: ClientConfig = ClientConfig()): Stream[F, TypeSafeClientF[F]] =
    Stream.resource(TypeSafeClientF.resource[F](config))

  /** Shorthand for an explicit key with everything else defaulted. */
  def withApiKey[F[_]: Async](apiKey: String): Stream[F, TypeSafeClientF[F]] =
    resource[F](ClientConfig(apiKey = Some(apiKey)))
