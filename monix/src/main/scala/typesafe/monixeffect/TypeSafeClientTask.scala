package typesafe.monixeffect

import java.util.concurrent.{CancellationException, CompletableFuture, CompletionException, ExecutionException}
import monix.eval.Task
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

  /** Ask the questions of a [[typesafe.Rubric]] about `state` and decode the answers into it:
    * `client.ask[Triage](state)`.
    */
  def ask[R](using rubric: Rubric[R]): Asking[R] = Asking(rubric)

  final class Asking[R] private[TypeSafeClientTask] (rubric: Rubric[R]):
    def apply[S: ToJson](state: S, options: CallOptions = CallOptions.default): Task[R] =
      fromFuture(underlying.askAsync(using rubric)(state, options))

  object models:
    /** The models available to this API key. */
    def list(options: CallOptions = CallOptions.default): Task[ListModelsResponse] =
      fromFuture(underlying.models.listAsync(options))

  /** Release the underlying HTTP client. [[TypeSafeClientTask.use]] does this for you. */
  def close: Task[Unit] = Task.eval(underlying.close())

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

  /** Builds a client. Closing it is the caller's job; prefer [[use]]. */
  def create(config: ClientConfig = ClientConfig()): Task[TypeSafeClientTask] =
    Task.eval(new TypeSafeClientTask(TypeSafeClient(config)))

  /** Lifts a client you already own; closing it stays your job. */
  def fromClient(client: TypeSafeClient): TypeSafeClientTask =
    new TypeSafeClientTask(client)
