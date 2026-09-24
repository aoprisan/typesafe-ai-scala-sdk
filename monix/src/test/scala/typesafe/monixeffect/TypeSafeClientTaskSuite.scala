package typesafe.monixeffect

import monix.execution.Scheduler.Implicits.global
import scala.concurrent.duration.*
import typesafe.*

class TypeSafeClientTaskSuite extends munit.FunSuite:
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

  private def client: TypeSafeClientTask = TypeSafeClientTask.fromClient(TypeSafeClient(config))

  test("describes the call: nothing is sent until the task runs") {
    api.respond(_ => Reply(200, okBody))
    val task = client.systemOne("Stripe keeps failing.", questions)
    assertEquals(api.requests.size, 0)
    task.map { res =>
      assertEquals(res(urgent).noul, 0.9)
      assertEquals(api.requests.size, 1)
    }.runToFuture
  }

  test("models.list") {
    api.respond(_ => Reply(200, """{"models":[{"name":"jev-1","description":"d","release_date":"2025-01-01"}]}"""))
    client.models.list().map(res => assertEquals(res.models.map(_.name), Vector("jev-1"))).runToFuture
  }

  test("failures arrive as the SDK's own exception, not a CompletionException") {
    api.respond(_ => Reply(401, """{"error":{"message":"bad key"}}"""))
    client.systemOne("x", questions).attempt.map {
      case Left(e: ApiException) => assertEquals(e.status, 401)
      case other                 => fail(s"expected an ApiException, got $other")
    }.runToFuture
  }

  test("retries happen inside the task") {
    api.respond(n => if n == 1 then Reply(503) else Reply(200, okBody))
    client.systemOne("x", questions).map(res => assertEquals(res.meta.attempts, 2)).runToFuture
  }

  test("cancelling stops the retry loop instead of leaving it running") {
    api.respond(_ => Reply(503, delay = 200.millis)) // retryable: without cancellation this loops
    val running = client.systemOne("x", questions).runToFuture
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while api.requests.isEmpty && System.nanoTime() < deadline do Thread.sleep(5)
    running.cancel()
    Thread.sleep(700)
    assertEquals(api.requests.size, 1)
  }

  test("use builds a client, runs the body and closes it") {
    api.respond(_ => Reply(200, okBody))
    TypeSafeClientTask.use(config)(_.systemOne("x", questions)).map(res => assert(res(urgent).isYes(0.8))).runToFuture
  }

  test("the Either variants put the SDK's own failures in the value") {
    api.respond(_ => Reply(429, """{"detail":"slow down"}"""))
    val c = TypeSafeClientTask.fromClient(TypeSafeClient(config.copy(retry = RetryPolicy.none)))
    val checked =
      for
        one    <- c.systemOneEither("x", questions)
        models <- c.models.listEither()
      yield
        one match
          case Left(ApiException(429, _)) => ()
          case other                      => fail(s"expected a 429 in the Left, got $other")
        assert(models.left.exists(_.isInstanceOf[ApiException]), models.toString)
    checked.runToFuture
  }

  test("resource builds a client and closes it on release") {
    api.respond(_ => Reply(200, okBody))
    TypeSafeClientTask.resource(config).use(_.systemOneEither("x", questions)).map { res =>
      assertEquals(res.map(_(urgent).noul), Right(0.9))
    }.runToFuture
  }
