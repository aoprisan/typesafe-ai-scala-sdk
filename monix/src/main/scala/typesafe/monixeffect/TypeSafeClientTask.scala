package typesafe.monixeffect

import cats.effect.Resource
import java.util.concurrent.{CancellationException, CompletableFuture, CompletionException, ExecutionException}
import monix.eval.Task
import monix.execution.Scheduler
import typesafe.*

/** The [[typesafe.TypeSafeClient]] as `monix.eval.Task`.
  *
  * Nothing is sent until the `Task` runs, cancelling the task cancels the HTTP exchange, and
  * failures are the SDK's own [[typesafe.TypeSafeException]] types rather than a
  * `CompletionException` wrapper.
  *
  * {{{
  * import monix.eval.Task
  * import monix.execution.Scheduler.Implicits.global
  * import typesafe.*
  * import typesafe.monixeffect.*
  *
  * val urgent = Noul("The message conveys urgency").named("is_urgent")
  *
  * val task = TypeSafeClientTask.use() { client =>
  *   client.systemOne("My payouts have failed for 3 days!", Questions.of(urgent))
  * }
  * task.runToFuture.foreach(res => println(res(urgent).noul))
  * }}}
  */
final class TypeSafeClientTask private (val underlying: TypeSafeClient):

  override def toString: String = s"TypeSafeClientTask(${underlying.baseUrl}, model=${underlying.defaultModel})"

  /** The base URL this client talks to. */
  def baseUrl: String = underlying.baseUrl

  /** The model used when a call does not override it. */
  def defaultModel: String = underlying.defaultModel

  /** Ask typed questions about `state`. Retries (per the policy) happen inside the returned `Task`. */
  def systemOne[S: ToJson](
      state: S,
      questions: Questions,
      options: CallOptions = CallOptions.default
  ): Task[SystemOneResponse] =
    fromFuture(underlying.systemOneAsync(state, questions, options))

  /** As [[systemOne]], but the SDK's own failures come back in the value instead of the error
    * channel. [[typesafe.TypeSafeException]] is sealed, so matching on the `Left` is exhaustive;
    * anything else stays in the error channel, and cancellation is still cancellation.
    */
  def systemOneEither[S: ToJson](
      state: S,
      questions: Questions,
      options: CallOptions = CallOptions.default
  ): Task[Either[TypeSafeException, SystemOneResponse]] =
    narrow(systemOne(state, questions, options))

  /** Ask the questions of a [[typesafe.Rubric]] about `state` and decode the answers into it:
    * `client.ask[Triage](state)`.
    */
  def ask[R](using rubric: Rubric[R]): Asking[R] = Asking(rubric)

  /** As [[ask]], with the SDK's failures in the value. See [[systemOneEither]]. */
  def askEither[R](using rubric: Rubric[R]): AskingEither[R] = AskingEither(rubric)

  final class Asking[R] private[TypeSafeClientTask] (rubric: Rubric[R]):
    def apply[S: ToJson](state: S, options: CallOptions = CallOptions.default): Task[R] =
      fromFuture(underlying.askAsync(using rubric)(state, options))

  final class AskingEither[R] private[TypeSafeClientTask] (rubric: Rubric[R]):
    def apply[S: ToJson](state: S, options: CallOptions = CallOptions.default): Task[Either[TypeSafeException, R]] =
      narrow(Asking(rubric)(state, options))

  object models:
    /** The models available to this API key. */
    def list(options: CallOptions = CallOptions.default): Task[ListModelsResponse] =
      fromFuture(underlying.models.listAsync(options))

    /** As [[list]], with the SDK's failures in the value. See [[systemOneEither]]. */
    def listEither(options: CallOptions = CallOptions.default): Task[Either[TypeSafeException, ListModelsResponse]] =
      narrow(list(options))

  /** Release the underlying HTTP client. [[TypeSafeClientTask.use]] and [[TypeSafeClientTask.resource]]
    * do this for you.
    *
    * On JDK 21 closing waits for the exchanges in flight to finish, so it runs on an I/O scheduler
    * rather than tying up a compute thread.
    */
  def close: Task[Unit] = Task.eval(underlying.close()).executeOn(TypeSafeClientTask.blocking)

  /** Moves the SDK's own failures into the value, and leaves every other one alone. */
  private def narrow[A](task: Task[A]): Task[Either[TypeSafeException, A]] =
    task.map[Either[TypeSafeException, A]](Right(_)).onErrorRecover { case e: TypeSafeException => Left(e) }

  /** Suspends the call, unwraps the JDK's exception wrappers and cancels the exchange on cancellation. */
  private def fromFuture[A](start: => CompletableFuture[A]): Task[A] =
    Task.cancelable[A] { cb =>
      val running = start
      running.whenComplete { (a: A, err: Throwable) =>
        if err == null then cb.tryOnSuccess(a)
        else
          unwrap(err) match
            // Our own cancel token got there first; the task is already gone, so there is nobody to tell.
            case _: CancellationException => ()
            case e                        => cb.tryOnError(e)
        ()
      }
      Task.eval {
        running.cancel(true)
        ()
      }
    }

  private def unwrap(t: Throwable): Throwable = t match
    case e: CompletionException if e.getCause != null => unwrap(e.getCause)
    case e: ExecutionException if e.getCause != null  => unwrap(e.getCause)
    case e: CancellationException                     => e
    case other                                        => other

object TypeSafeClientTask:

  /** Builds a client, hands it to `f` and closes it afterwards, on success, failure or cancellation. */
  def use[A](config: ClientConfig = ClientConfig())(f: TypeSafeClientTask => Task[A]): Task[A] =
    create(config).bracket(f)(_.close)

  /** A client whose HTTP resources are released when the (Cats Effect 2) `Resource` is released. */
  def resource(config: ClientConfig = ClientConfig()): Resource[Task, TypeSafeClientTask] =
    Resource.make(create(config))(_.close)

  /** Builds a client. Closing it is the caller's job; prefer [[use]]. */
  def create(config: ClientConfig = ClientConfig()): Task[TypeSafeClientTask] =
    Task.eval(new TypeSafeClientTask(TypeSafeClient(config)))

  /** Lifts a client you already own; closing it stays your job. */
  def fromClient(client: TypeSafeClient): TypeSafeClientTask =
    new TypeSafeClientTask(client)

  /** Where [[TypeSafeClientTask.close]] runs: closing can block, and an I/O scheduler grows for that. */
  private lazy val blocking: Scheduler = Scheduler.io(name = "typesafe-close")
