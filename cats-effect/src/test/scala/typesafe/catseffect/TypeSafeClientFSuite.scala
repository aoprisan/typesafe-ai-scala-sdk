package typesafe.catseffect

import cats.effect.IO
import cats.effect.kernel.Outcome
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
