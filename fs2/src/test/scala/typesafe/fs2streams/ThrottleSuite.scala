package typesafe.fs2streams

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import scala.concurrent.duration.*

/** The bucket on virtual time: the schedule is asserted exactly, and a minute of rate limiting
  * costs the suite no wall clock.
  */
class ThrottleSuite extends munit.CatsEffectSuite:

  /** When each of `n` acquires gets through, relative to the start. */
  private def stamps(every: FiniteDuration, burst: Int, n: Int, parallel: Boolean = false): IO[List[FiniteDuration]] =
    TestControl.executeEmbed(
      for
        throttle <- Throttle[IO](every, burst)
        start    <- IO.monotonic
        one       = throttle.acquire *> IO.monotonic
        got      <- if parallel then List.fill(n)(one).parSequence else List.fill(n)(one).sequence
      yield got.map(_ - start).sorted
    )

  test("spaces acquires at the steady rate") {
    stamps(100.millis, burst = 1, n = 4).map { got =>
      assertEquals(got, List(0.millis, 100.millis, 200.millis, 300.millis))
    }
  }

  test("a burst goes at once, then the steady rate takes over") {
    stamps(100.millis, burst = 3, n = 5).map { got =>
      assertEquals(got, List(0.millis, 0.millis, 0.millis, 100.millis, 200.millis))
    }
  }

  test("concurrent acquires are spaced too: the bucket is not raced through") {
    stamps(100.millis, burst = 1, n = 4, parallel = true).map { got =>
      assertEquals(got, List(0.millis, 100.millis, 200.millis, 300.millis))
    }
  }

  test("an idle bucket banks at most `burst`, however long it has been quiet") {
    TestControl
      .executeEmbed(
        for
          throttle <- Throttle[IO](100.millis, burst = 2)
          _        <- throttle.acquire       // takes the bucket off its initial state
          _        <- IO.sleep(10.seconds)   // far longer than 2 intervals of unused rate
          start    <- IO.monotonic
          one       = throttle.acquire *> IO.monotonic
          got      <- List.fill(3)(one).sequence
        yield got.map(_ - start)
      )
      .map(got => assertEquals(got, List(0.millis, 0.millis, 100.millis)))
  }

  test("rejects a rate or a burst that cannot mean anything") {
    for
      rate  <- Throttle[IO](Duration.Zero, burst = 1).attempt
      burst <- Throttle[IO](100.millis, burst = 0).attempt
    yield
      assert(rate.left.exists(_.isInstanceOf[IllegalArgumentException]), s"unexpected: $rate")
      assert(burst.left.exists(_.isInstanceOf[IllegalArgumentException]), s"unexpected: $burst")
  }
