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

/** [[ox.flow.Flow]] operators that ask the same questions — or the same [[typesafe.Rubric]] — of every
  * element of a batch of states.
  *
  * The API has no streaming endpoint; what flows here is your own workload — a table, a queue, a
  * file of tickets — run through System One with a bounded number of calls in flight.
  *
  * Rate limiting is already Ox's job rather than this module's: `Flow#throttle` is built in, so a
  * quota goes on the flow itself.
  *
  * {{{
  * supervised {
  *   val client = TypeSafeOx.useInScope()
  *   Flow.fromIterable(tickets)
  *     .throttle(120, 1.minute)                        // stay inside the quota
  *     .systemOnePar(client, questions, maxConcurrent = 8)
  *     .runToList()
  * }
  * }}}
  */
extension [S](flow: Flow[S])(using ToJson[S])

  /** Answers for every state, in input order, at most `maxConcurrent` calls in flight.
    *
    * The first failure that survives the retry policy fails the flow.
    */
  def systemOnePar(
      client: TypeSafeClient,
      questions: Questions,
      maxConcurrent: Int = 4,
      options: CallOptions = CallOptions.default
  ): Flow[SystemOneResponse] =
    flow.mapPar(maxConcurrent)(client.systemOne(_, questions, options))

  /** As [[systemOnePar]], but answers are emitted as they arrive rather than in input order. */
  def systemOneParUnordered(
      client: TypeSafeClient,
      questions: Questions,
      maxConcurrent: Int = 4,
      options: CallOptions = CallOptions.default
  ): Flow[SystemOneResponse] =
    flow.mapParUnordered(maxConcurrent)(client.systemOne(_, questions, options))

  /** As [[systemOnePar]], but one bad state does not sink the run: each state is emitted with its
    * outcome, so failures can be logged, counted or retried later.
    *
    * The failure side is the sealed [[typesafe.TypeSafeException]] rather than `Throwable`, so
    * sorting the wreckage afterwards is a match the compiler checks.
    */
  def systemOneParEither(
      client: TypeSafeClient,
      questions: Questions,
      maxConcurrent: Int = 4,
      options: CallOptions = CallOptions.default
  ): Flow[(S, Either[TypeSafeException, SystemOneResponse])] =
    flow.mapPar(maxConcurrent)(state => state -> client.systemOneEither(state, questions, options))

  /** [[systemOnePar]] for a [[typesafe.Rubric]]: each state's answers decoded into `R`, in input
    * order, at most `maxConcurrent` calls in flight.
    *
    * {{{
    * Flow.fromIterable(tickets).askPar[Triage](client, maxConcurrent = 8).runToList()  // List[Triage]
    * }}}
    *
    * A response the rubric cannot hold fails the flow with [[typesafe.ResponseValidationException]],
    * as any other failure that survives the retry policy does.
    */
  def askPar[R](
      client: TypeSafeClient,
      maxConcurrent: Int = 4,
      options: CallOptions = CallOptions.default
  )(using rubric: Rubric[R]): Flow[R] =
    flow.mapPar(maxConcurrent)(client.ask(using rubric)(_, options))

  /** As [[askPar]], but answers are emitted as they arrive rather than in input order. */
  def askParUnordered[R](
      client: TypeSafeClient,
      maxConcurrent: Int = 4,
      options: CallOptions = CallOptions.default
  )(using rubric: Rubric[R]): Flow[R] =
    flow.mapParUnordered(maxConcurrent)(client.ask(using rubric)(_, options))

  /** [[systemOneParEither]] for a [[typesafe.Rubric]]: each state paired with its decoded answers or
    * the SDK's failure, so one bad state does not sink the run.
    */
  def askParEither[R](
      client: TypeSafeClient,
      maxConcurrent: Int = 4,
      options: CallOptions = CallOptions.default
  )(using rubric: Rubric[R]): Flow[(S, Either[TypeSafeException, R])] =
    flow.mapPar(maxConcurrent)(state => state -> client.askEither(using rubric)(state, options))
