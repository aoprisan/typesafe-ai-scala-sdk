package examples.catseffect

import cats.effect.{IO, IOApp}
import examples.FakeApi
import scala.concurrent.duration.*
import typesafe.*
import typesafe.catseffect.*

/** `F[A]` has no typed error channel, but `TypeSafeException` is sealed — so the SDK can hand its
  * own failures back as a `Left` the compiler checks, and a missing case is a compile error rather
  * than a surprise in production.
  *
  * {{{
  * sbt "examplesCatsEffect/runMain examples.catseffect.TypedFailures"
  * }}}
  */
object TypedFailures extends IOApp.Simple:

  private val urgent    = Noul("The message conveys urgency").named("is_urgent")
  private val questions = Questions.of(urgent)
  private val ticket    = "My payouts have failed for 3 days!"

  /** Deliberately never sent, to show what happens when an answer is read that is not there. */
  private val neverAsked = Noul("never asked").named("never_asked")

  /** Matches every branch the SDK can produce. Drop one and this stops compiling. */
  private def report(outcome: Either[TypeSafeException, SystemOneResponse]): IO[Unit] =
    outcome match
      case Right(res)                        => IO.println(f"  ok        urgency ${res(urgent).noul}%.3f")
      case Left(e: ApiException)             => IO.println(s"  api       ${e.status} ${e.kind}: ${e.detail}")
      case Left(e: TimeoutException)         => IO.println(s"  timeout   gave up after ${e.timeout}")
      case Left(e: ConnectionException)      => IO.println(s"  transport ${e.getMessage}")
      case Left(e: ResponseValidationException) => IO.println(s"  decode    ${e.fieldPath}: ${e.detail}")
      case Left(e: InvalidRequestException)  => IO.println(s"  local     ${e.getMessage}")
      case Left(e: ConfigException)          => IO.println(s"  config    ${e.getMessage}")

  /** One client per fake, each closed after its turn. */
  private def against(label: String, api: FakeApi)(call: TypeSafeClientF[IO] => IO[Unit]): IO[Unit] =
    val config = ClientConfig(
      apiKey  = Some("fake-key"),
      baseUrl = Some(api.baseUrl),
      retry   = RetryPolicy(maxRetries = 1, backoffInitial = 50.millis)
    )
    IO.println(label) *> TypeSafeClientF.resource[IO](config).use(call).guarantee(IO(api.stop()))

  def run: IO[Unit] =
    for
      _ <- against("answered:", FakeApi.start())(c => c.systemOneEither(ticket, questions).flatMap(report))
      _ <- against("rate limited:", FakeApi.replying((_, _) => (429, """{"error":{"message":"Slow down"}}""")))(
             c => c.systemOneEither(ticket, questions).flatMap(report)
           )
      _ <- against("bad body:", FakeApi.replying((_, _) => (200, """{"model":"m","usage":{},"answers":[]}""")))(
             c => c.systemOneEither(ticket, questions).flatMap(report)
           )
      // Only the SDK's own failures move into the value. A bug in your own code does not: reading
      // an answer you never asked for is a NoSuchElementException, and it stays in the error
      // channel where a bug belongs.
      _ <- against("not an SDK failure:", FakeApi.start())(c =>
             c.systemOneEither(ticket, questions)
               .flatMap {
                 case Right(res) => IO.println(s"  peeked    ${res(neverAsked).noul}")
                 case Left(e)    => report(Left(e))
               }
               .handleErrorWith(e => IO.println(s"  raised    ${e.getClass.getSimpleName}: ${e.getMessage}"))
           )
    yield ()
