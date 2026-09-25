package typesafe.fs2streams

import cats.effect.kernel.Async
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import scala.concurrent.duration.FiniteDuration
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
  * TypeSafeStream.stream[IO]().flatMap { client =>
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
    *
    * The failure side is the sealed [[typesafe.TypeSafeException]], as from
    * [[typesafe.catseffect.TypeSafeClientF.systemOneEither]], so sorting the wreckage afterwards is
    * a match the compiler checks. A failure that is not the SDK's own (a bug) still fails the stream.
    */
  def systemOneEitherPipe[S: ToJson](
      questions: Questions,
      maxConcurrent: Int = 4,
      options: CallOptions = CallOptions.default
  ): Pipe[F, S, (S, Either[TypeSafeException, SystemOneResponse])] =
    _.parEvalMap(maxConcurrent)(state => F.map(client.systemOneEither(state, questions, options))(state -> _))

  /** As [[systemOnePipe]], but calls also start no faster than one every `every`.
    *
    * `maxConcurrent` bounds how many calls are *in flight*; this bounds how fast they are *started*,
    * which is the shape a per-minute quota actually takes. Without it a burst of states walks
    * straight into `429 Too Many Requests`, and the retry policy then spends the call's budget
    * waiting out a limit the stream could have respected in the first place.
    *
    * `burst` is how many calls may go at once after an idle stretch; the default of 1 spaces every
    * call evenly. The rate is per pipe, and a fresh bucket is taken each time the stream is run.
    *
    * {{{
    * // 120 calls a minute, in bursts of up to 10
    * Stream.emits(tickets).through(client.systemOneThrottledPipe(questions, every = 500.millis, burst = 10))
    * }}}
    */
  def systemOneThrottledPipe[S: ToJson](
      questions: Questions,
      every: FiniteDuration,
      burst: Int = 1,
      maxConcurrent: Int = 4,
      options: CallOptions = CallOptions.default
  ): Pipe[F, S, SystemOneResponse] =
    in =>
      Stream.eval(Throttle[F](every, burst)).flatMap { throttle =>
        in.parEvalMap(maxConcurrent)(state => throttle.acquire *> client.systemOne(state, questions, options))
      }

  /** [[systemOneThrottledPipe]] with [[systemOneEitherPipe]]'s outcomes: the pairing you want for a
    * long run against a rate-limited key, where one bad state should not cost you the whole batch.
    */
  def systemOneThrottledEitherPipe[S: ToJson](
      questions: Questions,
      every: FiniteDuration,
      burst: Int = 1,
      maxConcurrent: Int = 4,
      options: CallOptions = CallOptions.default
  ): Pipe[F, S, (S, Either[TypeSafeException, SystemOneResponse])] =
    in =>
      Stream.eval(Throttle[F](every, burst)).flatMap { throttle =>
        in.parEvalMap(maxConcurrent) { state =>
          F.map(throttle.acquire *> client.systemOneEither(state, questions, options))(state -> _)
        }
      }

  /** [[systemOnePipe]] for a [[typesafe.Rubric]]: each state's answers decoded into `R`, in input
    * order, at most `maxConcurrent` calls in flight.
    *
    * {{{
    * Stream.emits(tickets).through(client.askPipe[Triage](maxConcurrent = 8))  // Stream[IO, Triage]
    * }}}
    *
    * A response the rubric cannot hold fails the stream with [[typesafe.ResponseValidationException]],
    * as any other failure that survives the retry policy does.
    */
  def askPipe[R](using rubric: Rubric[R]): AskPipe[F, R] = AskPipe(client, rubric)

  /** As [[askPipe]], but answers are emitted as they arrive rather than in input order. */
  def askUnorderedPipe[R](using rubric: Rubric[R]): AskUnorderedPipe[F, R] = AskUnorderedPipe(client, rubric)

  /** [[systemOneEitherPipe]] for a [[typesafe.Rubric]]: each state paired with its decoded answers or
    * the SDK's failure, so one bad state does not sink the run.
    */
  def askEitherPipe[R](using rubric: Rubric[R]): AskEitherPipe[F, R] = AskEitherPipe(client, rubric)

object TypeSafeStream:

  /** A single-element stream of a client whose HTTP resources close when the stream finishes; the
    * stream form of [[typesafe.catseffect.TypeSafeClientF.resource]].
    */
  def stream[F[_]: Async](config: ClientConfig = ClientConfig()): Stream[F, TypeSafeClientF[F]] =
    Stream.resource(TypeSafeClientF.resource[F](config))

  /** Shorthand for an explicit key with everything else defaulted. */
  def withApiKey[F[_]: Async](apiKey: String): Stream[F, TypeSafeClientF[F]] =
    stream[F](ClientConfig(apiKey = Some(apiKey)))

/** `client.askPipe[R]`, waiting for the pipe's settings: `client.askPipe[Triage](maxConcurrent = 8)`. */
final class AskPipe[F[_], R] private[fs2streams] (client: TypeSafeClientF[F], rubric: Rubric[R])(using Async[F]):
  def apply[S: ToJson](maxConcurrent: Int = 4, options: CallOptions = CallOptions.default): Pipe[F, S, R] =
    _.parEvalMap(maxConcurrent)(client.ask(using rubric)(_, options))

/** `client.askUnorderedPipe[R]`, waiting for the pipe's settings. */
final class AskUnorderedPipe[F[_], R] private[fs2streams] (client: TypeSafeClientF[F], rubric: Rubric[R])(using Async[F]):
  def apply[S: ToJson](maxConcurrent: Int = 4, options: CallOptions = CallOptions.default): Pipe[F, S, R] =
    _.parEvalMapUnordered(maxConcurrent)(client.ask(using rubric)(_, options))

/** `client.askEitherPipe[R]`, waiting for the pipe's settings. */
final class AskEitherPipe[F[_], R] private[fs2streams] (client: TypeSafeClientF[F], rubric: Rubric[R])(using F: Async[F]):
  def apply[S: ToJson](
      maxConcurrent: Int = 4,
      options: CallOptions = CallOptions.default
  ): Pipe[F, S, (S, Either[TypeSafeException, R])] =
    _.parEvalMap(maxConcurrent)(state => F.map(client.askEither(using rubric)(state, options))(state -> _))
