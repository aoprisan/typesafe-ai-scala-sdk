package typesafe.fs2streams

import cats.effect.IO
import fs2.Stream
import scala.concurrent.duration.*
import typesafe.*
import typesafe.catseffect.*
import typesafe.rubric.*

/** The rubric the ask pipes decode into: the same single question the suite asks by hand. */
final case class Urgency(@noul("The message conveys urgency") isUrgent: Double) derives Rubric

class PipesSuite extends munit.CatsEffectSuite:
  private val api = MockApi()
  import api.{Received, Reply}

  override def afterAll(): Unit = api.stop()

  private val urgent = Noul("The message conveys urgency").named("is_urgent")
  private val questions = Questions.of(urgent)
  private val states = (0 until 6).map(i => s"ticket-$i").toVector

  /** The state a request carries, e.g. `ticket-3` -> 3. */
  private def indexOf(request: Received): Int =
    Json.unsafeParse(request.body).get("state").flatMap(_.asString).map(_.stripPrefix("ticket-").toInt).get

  private def answer(index: Int): String =
    s"""{"model":"jev-latest","answers":{"is_urgent":{"type":"noul","noul":0.$index}},"usage":{"input_tokens":1,"output_tokens":1}}"""

  private def client: TypeSafeClientF[IO] = TypeSafeClient(
    ClientConfig(apiKey = Some("sk-test"), baseUrl = Some(api.url), retry = RetryPolicy(maxRetries = 0), env = _ => None)
  ).effect[IO]

  /** Later tickets answer sooner, so completion order is the reverse of input order. */
  private def answerInReverse(): Unit =
    api.respondTo { r =>
      val i = indexOf(r)
      Reply(200, answer(i), delay = (states.size - i) * 60.millis)
    }

  private def nouls(s: Stream[IO, SystemOneResponse]): IO[Vector[Double]] =
    s.map(_(urgent).noul).compile.toVector

  test("ordered pipe keeps input order however the answers arrive") {
    answerInReverse()
    nouls(Stream.emits(states).through(client.systemOnePipe(questions, maxConcurrent = states.size))).map { got =>
      assertEquals(got, states.indices.map(i => s"0.$i".toDouble).toVector)
      assertEquals(api.requests.size, states.size)
    }
  }

  test("unordered pipe emits as answers arrive") {
    answerInReverse()
    nouls(Stream.emits(states).through(client.systemOneUnorderedPipe(questions, maxConcurrent = states.size))).map { got =>
      assertEquals(got.sorted, states.indices.map(i => s"0.$i".toDouble).toVector)
      assertNotEquals(got, got.sorted, "answers should not have been re-ordered back into input order")
    }
  }

  test("maxConcurrent bounds the calls in flight") {
    val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
    val peak = java.util.concurrent.atomic.AtomicInteger(0)
    api.respondTo { r =>
      peak.accumulateAndGet(inFlight.incrementAndGet(), math.max)
      Reply(200, answer(indexOf(r)), delay = 50.millis)
    }
    // `delay` is served before the handler returns, so decrementing after the reply is built is enough
    // to see overlap: every in-flight request has incremented and not yet finished its delay.
    Stream
      .emits(states)
      .through(client.systemOnePipe(questions, maxConcurrent = 2))
      .evalTap(_ => IO(inFlight.decrementAndGet()))
      .compile
      .drain
      .map { _ =>
        assert(peak.get() <= 2, s"saw ${peak.get()} calls in flight")
        assert(peak.get() > 1, "calls should have overlapped at all")
      }
  }

  test("either pipe pairs each state with its outcome and keeps the stream alive") {
    api.respondTo { r =>
      val i = indexOf(r)
      if i % 2 == 0 then Reply(200, answer(i)) else Reply(400, """{"error":{"message":"nope"}}""")
    }
    Stream
      .emits(states)
      .through(client.systemOneEitherPipe(questions, maxConcurrent = 3))
      .compile
      .toVector
      .map { got =>
        assertEquals(got.map(_._1), states)
        got.zipWithIndex.foreach {
          case ((_, Right(res)), i) if i % 2 == 0 => assertEquals(res(urgent).noul, s"0.$i".toDouble)
          case ((_, Left(e: ApiException)), i)    => assertEquals(e.status, 400, s"ticket $i")
          case (other, i)                         => fail(s"unexpected outcome for ticket $i: $other")
        }
      }
  }

  test("the throttled pipe spaces the calls and still answers in input order") {
    api.respondTo(r => Reply(200, answer(indexOf(r))))
    val every = 80.millis
    for
      start <- IO.monotonic
      got   <- nouls(
                 Stream
                   .emits(states)
                   .through(client.systemOneThrottledPipe(questions, every = every, maxConcurrent = states.size))
               )
      end   <- IO.monotonic
    yield
      assertEquals(got, states.indices.map(i => s"0.$i".toDouble).toVector)
      assertEquals(api.requests.size, states.size)
      // `maxConcurrent` would have let all six go at once; the rate is what holds them apart.
      val floor = every * (states.size - 1).toLong
      assert(end - start >= floor, s"six calls one per $every should take at least $floor, took ${end - start}")
  }

  test("a burst is allowed through before the rate bites") {
    api.respondTo(r => Reply(200, answer(indexOf(r))))
    for
      start <- IO.monotonic
      _     <- nouls(
                 Stream
                   .emits(states.take(3))
                   .through(client.systemOneThrottledPipe(questions, every = 1.second, burst = 3, maxConcurrent = 3))
               )
      end   <- IO.monotonic
    yield assert(end - start < 1.second, s"a burst of three should not have waited, took ${end - start}")
  }

  test("the throttled either pipe keeps each state with its outcome") {
    api.respondTo { r =>
      val i = indexOf(r)
      if i % 2 == 0 then Reply(200, answer(i)) else Reply(400, """{"error":{"message":"nope"}}""")
    }
    Stream
      .emits(states)
      .through(client.systemOneThrottledEitherPipe(questions, every = 10.millis, maxConcurrent = 3))
      .compile
      .toVector
      .map { got =>
        assertEquals(got.map(_._1), states)
        assertEquals(got.count(_._2.isRight), 3)
        got.zipWithIndex.foreach {
          case ((_, Right(res)), i) if i % 2 == 0 => assertEquals(res(urgent).noul, s"0.$i".toDouble)
          case ((_, Left(e: ApiException)), i)    => assertEquals(e.status, 400, s"ticket $i")
          case (other, i)                         => fail(s"unexpected outcome for ticket $i: $other")
        }
      }
  }

  test("askPipe decodes each state's answers into the rubric, in input order") {
    answerInReverse()
    Stream
      .emits(states)
      .through(client.askPipe[Urgency](maxConcurrent = states.size))
      .compile
      .toVector
      .map { got =>
        assertEquals(got, states.indices.map(i => Urgency(s"0.$i".toDouble)).toVector)
        assertEquals(Json.unsafeParse(api.requests.head.body).get("questions"), Some(Rubric[Urgency].questions.toJson))
      }
  }

  test("askUnorderedPipe emits as answers arrive") {
    answerInReverse()
    Stream.emits(states).through(client.askUnorderedPipe[Urgency](maxConcurrent = states.size)).compile.toVector.map { got =>
      val nouls = got.map(_.isUrgent)
      assertEquals(nouls.sorted, states.indices.map(i => s"0.$i".toDouble).toVector)
      assertNotEquals(nouls, nouls.sorted, "answers should not have been re-ordered back into input order")
    }
  }

  test("askEitherPipe keeps each state with its decoded answers or the SDK's failure") {
    api.respondTo { r =>
      indexOf(r) match
        case 1 => Reply(400, """{"error":{"message":"nope"}}""")
        // An answer of another type: the rubric cannot hold it.
        case 2 => Reply(200, """{"model":"m","answers":{"is_urgent":{"type":"score","score":1,"confidence":1,"legend":{},"probabilities":{}}},"usage":{}}""")
        case i => Reply(200, answer(i))
    }
    Stream.emits(states).through(client.askEitherPipe[Urgency](maxConcurrent = 3)).compile.toVector.map { got =>
      assertEquals(got.map(_._1), states)
      got.zipWithIndex.foreach {
        case ((_, Left(e: ApiException)), 1)                => assertEquals(e.status, 400)
        case ((_, Left(e: ResponseValidationException)), 2) => assertEquals(e.fieldPath, "answers.is_urgent")
        case ((_, Right(u)), i) if i > 2 || i == 0          => assertEquals(u, Urgency(s"0.$i".toDouble))
        case (other, i)                                     => fail(s"unexpected outcome for ticket $i: $other")
      }
    }
  }
