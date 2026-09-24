package typesafe.oxdirect

import ox.flow.Flow
import typesafe.*

// `systemOneEither`, `askEither` and `modelsEither` used to be extensions here. They are members of
// `TypeSafeClient` now, with the same signatures and the same narrowing (only `TypeSafeException`
// goes into the `Left`; `InterruptedException`, how a supervised scope winds a fork down, stays an
// exception), so `client.systemOneEither(...)` compiles unchanged and needs no import. Ox's `either`
// blocks take them as they are:
//
//   import ox.either.*
//   val summary: Either[TypeSafeException, String] = either:
//     val res = client.systemOneEither(ticket, questions).ok()
//     s"urgency ${res(urgent).noul}"

/** What the ox `askEither` extension used to return; `client.askEither` is now
  * [[typesafe.TypeSafeClient.askEither]], which returns the core's own `AskingEither`.
  */
@deprecated("client.askEither is now a member of TypeSafeClient and returns TypeSafeClient#AskingEither", "0.4.0")
final class AskingEither[R] private[oxdirect] (client: TypeSafeClient, rubric: Rubric[R]):
  def apply[S: ToJson](state: S, options: CallOptions = CallOptions.default): Either[TypeSafeException, R] =
    client.askEither(using rubric)(state, options)

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
