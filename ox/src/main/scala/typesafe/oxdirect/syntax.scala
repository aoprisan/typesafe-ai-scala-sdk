package typesafe.oxdirect

import ox.flow.Flow
import typesafe.*

/** Moves the SDK's own failures into the value and leaves every other one alone.
  *
  * Catching `TypeSafeException` and nothing else is deliberate: `InterruptedException` is how a
  * supervised scope winds a fork down, so turning it into a `Left` would quietly swallow a
  * cancellation. It stays an exception, as do bugs.
  */
private def narrow[A](call: => A): Either[TypeSafeException, A] =
  try Right(call)
  catch case e: TypeSafeException => Left(e)

extension (client: TypeSafeClient)

  /** As `systemOne`, but the SDK's own failures come back in the value.
    *
    * [[typesafe.TypeSafeException]] is sealed, so the compiler checks the match for you, and the
    * result drops straight into an `either` block:
    *
    * {{{
    * import ox.either.*
    *
    * val summary: Either[TypeSafeException, String] = either:
    *   val res = client.systemOneEither(ticket, questions).ok()
    *   s"urgency ${res(urgent).noul}"
    * }}}
    */
  def systemOneEither[S: ToJson](
      state: S,
      questions: Questions,
      options: CallOptions = CallOptions.default
  ): Either[TypeSafeException, SystemOneResponse] =
    narrow(client.systemOne(state, questions, options))

  /** The models available to this API key, with the SDK's failures in the value. */
  def modelsEither(options: CallOptions = CallOptions.default): Either[TypeSafeException, ListModelsResponse] =
    narrow(client.models.list(options))

  /** As `client.ask[R]`, with the SDK's failures in the value: `client.askEither[Triage](state)`. */
  def askEither[R](using rubric: Rubric[R]): AskingEither[R] = AskingEither(client, rubric)

/** A pending [[askEither]]: give it the state. */
final class AskingEither[R] private[oxdirect] (client: TypeSafeClient, rubric: Rubric[R]):
  def apply[S: ToJson](state: S, options: CallOptions = CallOptions.default): Either[TypeSafeException, R] =
    narrow(client.ask(using rubric)(state, options))

/** [[ox.flow.Flow]] operators that ask the same questions of every element of a batch of states.
  *
  * The API has no streaming endpoint; what flows here is your own workload — a table, a queue, a
  * file of tickets — run through System One with a bounded number of calls in flight.
  *
  * Rate limiting is already Ox's job rather than this module's: `Flow#throttle` is built in, so a
  * quota goes on the flow itself.
  *
  * {{{
  * supervised {
  *   val client = TypeSafeOx.inScope()
  *   Flow.fromIterable(tickets)
  *     .throttle(120, 1.minute)                        // stay inside the quota
  *     .systemOnePar(client, questions, parallelism = 8)
  *     .runToList()
  * }
  * }}}
  */
extension [S](flow: Flow[S])(using ToJson[S])

  /** Answers for every state, in input order, at most `parallelism` calls in flight.
    *
    * The first failure that survives the retry policy fails the flow.
    */
  def systemOnePar(
      client: TypeSafeClient,
      questions: Questions,
      parallelism: Int = 4,
      options: CallOptions = CallOptions.default
  ): Flow[SystemOneResponse] =
    flow.mapPar(parallelism)(client.systemOne(_, questions, options))

  /** As [[systemOnePar]], but answers are emitted as they arrive rather than in input order. */
  def systemOneParUnordered(
      client: TypeSafeClient,
      questions: Questions,
      parallelism: Int = 4,
      options: CallOptions = CallOptions.default
  ): Flow[SystemOneResponse] =
    flow.mapParUnordered(parallelism)(client.systemOne(_, questions, options))

  /** As [[systemOnePar]], but one bad state does not sink the run: each state is emitted with its
    * outcome, so failures can be logged, counted or retried later.
    *
    * The failure side is the sealed [[typesafe.TypeSafeException]] rather than `Throwable`, so
    * sorting the wreckage afterwards is a match the compiler checks.
    */
  def systemOneParEither(
      client: TypeSafeClient,
      questions: Questions,
      parallelism: Int = 4,
      options: CallOptions = CallOptions.default
  ): Flow[(S, Either[TypeSafeException, SystemOneResponse])] =
    flow.mapPar(parallelism)(state => state -> client.systemOneEither(state, questions, options))
