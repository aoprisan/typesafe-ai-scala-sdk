package examples.catseffect

import cats.effect.{IO, IOApp}
import cats.effect.std.Random
import examples.FakeApi
import scala.concurrent.duration.*
import typesafe.*
import typesafe.catseffect.*

/** The SDK ships no logging or metrics dependency, so `withOnRetry` is the seam where yours goes:
  * log4cats, otel4s or, as here, `IO.println` and a counter.
  *
  * `withRandom` is the other half of the story. The backoff is jittered, so a schedule is only
  * reproducible once the jitter has a seed you chose — and with `TestControl` for the waiting, a
  * jittered retry becomes assertable to the millisecond instead of to a range.
  *
  * {{{
  * sbt "examplesCatsEffect/runMain examples.catseffect.WatchingRetries"
  * }}}
  */
object WatchingRetries extends IOApp.Simple:

  private val urgent    = Noul("The message conveys urgency").named("is_urgent")
  private val questions = Questions.of(urgent)
  private val ticket    = "My payouts have failed for 3 days!"

  def run: IO[Unit] = retried *> noRetries

  /** Two 503s, then an answer, with every wait in between reported. */
  private def retried: IO[Unit] =
    val api = FakeApi.start(failFirst = 2)
    val config = ClientConfig(
      apiKey  = Some("fake-key"),
      baseUrl = Some(api.baseUrl),
      retry   = RetryPolicy(maxRetries = 3, backoffInitial = 200.millis, backoffMax = 2.seconds)
    )

    TypeSafeClientF
      .resource[IO](config)
      .use { plain =>
        for
          // A seeded jitter source: run this twice and the delays below are identical.
          seeded <- Random.scalaUtilRandomSeedInt[IO](42)
          client  = plain.withRandom(seeded).withOnRetry { ev =>
                      // The observer cannot change the outcome — its result is discarded and its
                      // own failure is swallowed, because a broken counter should not fail a
                      // request that was about to succeed.
                      IO.println(f"  retry   ${ev.endpoint} attempt ${ev.attempt} failed " +
                        f"(${ev.error.getClass.getSimpleName}), waiting ${ev.delay.toMillis}%d ms")
                    }
          res    <- client.systemOne(ticket, questions)
          _      <- IO.println(f"answered  urgency ${res(urgent).noul}%.3f on attempt ${res.meta.attempts}")
        yield ()
      }
      .guarantee(IO(api.stop()))

  /** An event fires only when another attempt really is coming: the failure that exhausts the
    * policy is raised, not announced, so this run reports the failure and no retry at all.
    */
  private def noRetries: IO[Unit] =
    val api = FakeApi.replying((_, _) => (503, """{"error":{"message":"Overloaded"}}"""))
    val config = ClientConfig(apiKey = Some("fake-key"), baseUrl = Some(api.baseUrl), retry = RetryPolicy.none)
    TypeSafeClientF
      .resource[IO](config)
      .use { client =>
        client
          .withOnRetry(ev => IO.println(s"  retry   $ev"))
          .systemOneEither(ticket, questions)
          .flatMap {
            case Left(e: ApiException) => IO.println(s"no retry  ${e.status} raised straight away")
            case other                 => IO.println(s"unexpected: $other")
          }
      }
      .guarantee(IO(api.stop()))
