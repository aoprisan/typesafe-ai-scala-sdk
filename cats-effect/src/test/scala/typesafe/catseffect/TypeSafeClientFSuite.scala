package typesafe.catseffect

import cats.effect.IO
import cats.effect.kernel.Outcome
import cats.effect.std.Random
import scala.concurrent.duration.*
import typesafe.*

class TypeSafeClientFSuite extends munit.CatsEffectSuite:
  private val api = MockApi()
  import api.Reply

  override def afterAll(): Unit = api.stop()

  private val urgent = Noul("The message conveys urgency").named("is_urgent")
  private val questions = Questions.of(urgent)

  private val okBody =
    """{"model":"jev-latest","answers":{"is_urgent":{"type":"noul","noul":0.9}},
      |"usage":{"input_tokens":12,"output_tokens":3}}""".stripMargin

  private val config = ClientConfig(
    apiKey = Some("sk-test"),
    baseUrl = Some(api.url),
    retry = RetryPolicy(backoffInitial = 1.milli, backoffMax = 5.millis),
    env = _ => None
  )

  private def client: TypeSafeClientF[IO] = TypeSafeClient(config).effect[IO]

  test("describes the call: nothing is sent until the IO runs") {
    api.respond(_ => Reply(200, okBody))
    val call = client.systemOne("Stripe keeps failing.", questions)
    IO(assertEquals(api.requests.size, 0)) *>
      call.map(res => assertEquals(res(urgent).noul, 0.9)) *>
      IO(assertEquals(api.requests.size, 1))
  }

  test("models.list") {
    api.respond(_ => Reply(200, """{"models":[{"name":"jev-1","description":"d","release_date":"2025-01-01"}]}"""))
    client.models.list().map { res =>
      assertEquals(res.models.map(_.name), Vector("jev-1"))
      assertEquals(api.requests.head.method, "GET")
    }
  }

  test("failures arrive as the SDK's own exception, not a CompletionException") {
    api.respond(_ => Reply(401, """{"error":{"message":"bad key"}}"""))
    client.systemOne("x", questions).attempt.map {
      case Left(e: ApiException) => assertEquals(e.status, 401)
      case other                 => fail(s"expected an ApiException, got $other")
    }
  }

  test("retries happen inside the IO") {
    api.respond(n => if n == 1 then Reply(503) else Reply(200, okBody))
    client.systemOne("x", questions).map { res =>
      assertEquals(res.meta.attempts, 2)
      assertEquals(api.requests.size, 2)
    }
  }

  test("cancelling the fiber cancels the in-flight request") {
    api.respond(_ => Reply(200, okBody, stall = 3.seconds))
    for
      fiber   <- client.systemOne("x", questions).start
      _       <- (IO.sleep(10.millis) *> IO(api.requests.size)).iterateUntil(_ == 1).timeout(5.seconds)
      _       <- fiber.cancel.timeout(1.second)
      outcome <- fiber.join
    yield outcome match
      case Outcome.Canceled() => ()
      case other              => fail(s"expected the fiber to be cancelled, got $other")
  }

  test("cancelling stops the retry loop instead of leaving it running") {
    api.respond(_ => Reply(503, delay = 200.millis)) // retryable: without cancellation this loops
    for
      fiber <- client.systemOne("x", questions).start
      _     <- (IO.sleep(10.millis) *> IO(api.requests.size)).iterateUntil(_ == 1).timeout(5.seconds)
      _     <- fiber.cancel.timeout(1.second)
      _     <- IO.sleep(1.second)
    yield assertEquals(api.requests.size, 1)
  }

  test("resource closes the client") {
    api.respond(_ => Reply(200, okBody))
    TypeSafeClientF.resource[IO](config).use(_.systemOne("x", questions)).map(res => assert(res(urgent).isYes(0.8)))
  }

  test("systemOneEither puts the SDK's own failure in the value") {
    api.respond(_ => Reply(401, """{"error":{"message":"bad key"}}"""))
    client.systemOneEither("x", questions).map {
      case Left(e: ApiException) => assertEquals(e.status, 401)
      case other                 => fail(s"expected a Left(ApiException), got $other")
    }
  }

  test("systemOneEither passes a good answer straight through") {
    api.respond(_ => Reply(200, okBody))
    client.systemOneEither("x", questions).map {
      case Right(res) => assertEquals(res(urgent).noul, 0.9)
      case other      => fail(s"expected a Right, got $other")
    }
  }

  test("models.listEither narrows the same way") {
    api.respond(_ => Reply(403, """{"error":{"message":"no models for you"}}"""))
    client.models.listEither().map {
      case Left(e: ApiException) => assertEquals(e.status, 403)
      case other                 => fail(s"expected a Left(ApiException), got $other")
    }
  }

  test("the Left is the sealed hierarchy, so matching it needs no catch-all") {
    api.respond(_ => Reply(400, """{"error":{"message":"nope"}}"""))
    client.systemOneEither("x", questions).map { out =>
      // There is deliberately no `case _ =>` below: this compiles only while the failure type stays
      // sealed and narrow, which is the whole point of the Either-returning calls. Widen it to
      // Throwable and the compiler starts asking for a catch-all here.
      val described = out match
        case Right(_)                             => "ok"
        case Left(_: ConfigException)             => "config"
        case Left(_: InvalidRequestException)     => "request"
        case Left(_: ApiException)                => "api"
        case Left(_: ConnectionException)         => "connection"
        case Left(_: TimeoutException)            => "timeout"
        case Left(_: ResponseValidationException) => "response"
      assertEquals(described, "api")
    }
  }

  test("cancelling an Either-returning call is still cancellation, not a Left") {
    api.respond(_ => Reply(200, okBody, stall = 3.seconds))
    for
      fiber   <- client.systemOneEither("x", questions).start
      _       <- (IO.sleep(10.millis) *> IO(api.requests.size)).iterateUntil(_ == 1).timeout(5.seconds)
      _       <- fiber.cancel.timeout(1.second)
      outcome <- fiber.join
    yield outcome match
      case Outcome.Canceled() => ()
      case other              => fail(s"expected the fiber to be cancelled, got $other")
  }

  test("withOnRetry reports the endpoint and the attempt that failed") {
    api.respond(n => if n == 1 then Reply(503) else Reply(200, okBody))
    for
      seen   <- IO.ref(Vector.empty[RetryEvent])
      _      <- client.withOnRetry(ev => seen.update(_ :+ ev)).systemOne("x", questions)
      events <- seen.get
    yield
      assertEquals(events.size, 1)
      assertEquals(events.head.endpoint, s"POST ${api.url}/v1/systemone")
      assertEquals(events.head.attempt, 1)
      assert(events.head.error.isInstanceOf[ApiException], s"unexpected error: ${events.head.error}")
  }

  test("withRandom feeds the backoff, so the same seed retries on the same schedule") {
    def delaysWithSeed(seed: Int): IO[Vector[FiniteDuration]] =
      for
        _      <- IO(api.respond(n => if n <= 2 then Reply(503) else Reply(200, okBody)))
        seen   <- IO.ref(Vector.empty[FiniteDuration])
        random <- Random.scalaUtilRandomSeedInt[IO](seed)
        _      <- client.withRandom(random).withOnRetry(ev => seen.update(_ :+ ev.delay)).systemOne("x", questions)
        got    <- seen.get
      yield got

    for
      first  <- delaysWithSeed(7)
      second <- delaysWithSeed(7)
    yield
      assertEquals(first.size, 2)
      // The policy's jitter only ever shortens a wait, so a seeded draw pins it exactly; with the
      // ambient ThreadLocalRandom these two runs would not line up to the nanosecond.
      assertEquals(first, second)
  }
