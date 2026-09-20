package typesafe.catseffect

import cats.effect.IO
import cats.effect.testkit.TestControl
import scala.concurrent.duration.*
import typesafe.*

/** The retry loop on virtual time: a half-minute of backoff costs a test no wall clock at all, and
  * the schedule is asserted exactly rather than approximately.
  */
class RetrySuite extends munit.CatsEffectSuite:

  private val noJitter = IO.pure(0.0)
  private def connectionLost = ConnectionException(java.io.IOException("connection reset"))

  /** Runs `io` and reports how long it took and how it ended. */
  private def timed[A](io: IO[A]): IO[(FiniteDuration, Either[Throwable, A])] =
    for
      start <- IO.monotonic
      out   <- io.attempt
      end   <- IO.monotonic
    yield (end - start, out)

  /** Counts the attempts a run makes, alongside its timing and outcome. */
  private def run[A](policy: RetryPolicy, attemptTimeout: FiniteDuration = 10.seconds)(
      attempt: Int => IO[A]
  ): IO[(Int, FiniteDuration, Either[Throwable, A])] =
    IO.ref(0).flatMap { attempts =>
      timed(Retry(policy, attemptTimeout, noJitter)(n => attempts.update(_ + 1) *> attempt(n)))
        .flatMap((elapsed, out) => attempts.get.map((_, elapsed, out)))
    }

  test("waits the policy's backoff between attempts") {
    val policy = RetryPolicy(maxRetries = 3, backoffInitial = 1.second, backoffJitter = 0, budget = None)
    TestControl.executeEmbed(run(policy)(_ => IO.raiseError[Unit](connectionLost))).map { (attempts, elapsed, out) =>
      assertEquals(attempts, 4) // the first try plus three retries
      assertEquals(elapsed, 7.seconds) // 1s + 2s + 4s of backoff, no jitter
      assert(out.left.exists(_.isInstanceOf[ConnectionException]), s"unexpected outcome: $out")
    }
  }

  test("the backoff is capped") {
    val policy = RetryPolicy(maxRetries = 4, backoffInitial = 1.second, backoffMax = 2.seconds, backoffJitter = 0, budget = None)
    TestControl.executeEmbed(run(policy)(_ => IO.raiseError[Unit](connectionLost))).map { (attempts, elapsed, _) =>
      assertEquals(attempts, 5)
      assertEquals(elapsed, 7.seconds) // 1s + 2s + 2s + 2s
    }
  }

  test("the retry budget stops the loop before a wait that would overrun it") {
    val policy =
      RetryPolicy(maxRetries = 10, backoffInitial = 1.second, backoffJitter = 0, budget = Some(5.seconds))
    TestControl.executeEmbed(run(policy)(_ => IO.raiseError[Unit](connectionLost))).map { (attempts, elapsed, out) =>
      assertEquals(attempts, 3) // a fourth attempt would wait 4s on top of 3s elapsed
      assertEquals(elapsed, 3.seconds)
      assert(out.isLeft)
    }
  }

  test("a failure the policy does not cover is raised at once") {
    val policy = RetryPolicy(maxRetries = 3, backoffInitial = 1.second, backoffJitter = 0)
    val boom = ApiException(400, None, Map.empty, Some("POST /v1/systemone"))
    TestControl.executeEmbed(run(policy)(_ => IO.raiseError[Unit](boom))).map { (attempts, elapsed, out) =>
      assertEquals(attempts, 1)
      assertEquals(elapsed, Duration.Zero)
      assertEquals(out, Left(boom))
    }
  }

  test("an attempt that hangs is timed out, and the timeout is retried like any other failure") {
    val policy = RetryPolicy(maxRetries = 1, backoffInitial = 1.second, backoffJitter = 0, budget = None)
    TestControl.executeEmbed(run(policy, attemptTimeout = 10.seconds)(_ => IO.never[Unit])).map {
      (attempts, elapsed, out) =>
        assertEquals(attempts, 2)
        assertEquals(elapsed, 21.seconds) // 10s attempt, 1s backoff, 10s attempt
        assert(out.left.exists(_.isInstanceOf[TimeoutException]), s"unexpected outcome: $out")
    }
  }

  test("a successful attempt does not wait at all") {
    val policy = RetryPolicy(maxRetries = 3, backoffInitial = 1.second, backoffJitter = 0)
    TestControl.executeEmbed(run(policy)(n => IO.pure(n))).map { (attempts, elapsed, out) =>
      assertEquals(attempts, 1)
      assertEquals(elapsed, Duration.Zero)
      assertEquals(out, Right(1))
    }
  }

  test("the observer is told about every wait, and not about the failure that gives up") {
    val policy = RetryPolicy(maxRetries = 2, backoffInitial = 1.second, backoffJitter = 0, budget = None)
    TestControl
      .executeEmbed(
        for
          seen   <- IO.ref(Vector.empty[(Int, FiniteDuration)])
          observe = (n: Int, _: Throwable, d: FiniteDuration) => seen.update(_ :+ (n, d))
          _      <- Retry(policy, 10.seconds, noJitter, Some(observe))(_ => IO.raiseError[Unit](connectionLost)).attempt
          events <- seen.get
        yield events
      )
      .map { events =>
        // Three attempts, so two waits; the third failure is raised rather than announced.
        assertEquals(events, Vector(1 -> 1.second, 2 -> 2.seconds))
      }
  }

  test("an observer that fails is not allowed to fail the call") {
    val policy = RetryPolicy(maxRetries = 1, backoffInitial = 1.second, backoffJitter = 0, budget = None)
    val observe = (_: Int, _: Throwable, _: FiniteDuration) => IO.raiseError[Unit](RuntimeException("metrics are down"))
    TestControl
      .executeEmbed(
        Retry(policy, 10.seconds, noJitter, Some(observe))(n =>
          if n == 1 then IO.raiseError[Int](connectionLost) else IO.pure(n)
        )
      )
      .map(attempt => assertEquals(attempt, 2))
  }
