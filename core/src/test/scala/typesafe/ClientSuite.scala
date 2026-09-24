package typesafe

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.immutable.VectorMap
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** A tiny programmable HTTP mock on the JDK's built-in server. */
final class MockApi:
  /** `delay` waits before the headers; `stall` sends the headers and `body` as a partial response, then waits. */
  final case class Reply(
      status: Int,
      body: String = "",
      headers: Map[String, String] = Map.empty,
      delay: FiniteDuration = Duration.Zero,
      stall: FiniteDuration = Duration.Zero
  )
  final case class Received(method: String, path: String, headers: Map[String, String], body: String):
    def header(name: String): Option[String] = headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v }

  private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  @volatile private var script: (Received, Int) => Reply = (_, _) => Reply(404)
  val received = ConcurrentLinkedQueue[Received]()

  server.createContext("/", exchange => {
    val body = String(exchange.getRequestBody.readAllBytes(), UTF_8)
    val headers = exchange.getRequestHeaders.asScala.map((k, v) => k -> v.asScala.mkString(",")).toMap
    val request = Received(exchange.getRequestMethod, exchange.getRequestURI.getPath, headers, body)
    received.add(request)
    val reply = script(request, received.size)
    if reply.delay > Duration.Zero then Thread.sleep(reply.delay.toMillis)
    reply.headers.foreach((k, v) => exchange.getResponseHeaders.add(k, v))
    val bytes = reply.body.getBytes(UTF_8)
    try
      if reply.stall > Duration.Zero then
        exchange.sendResponseHeaders(reply.status, bytes.length + 1000)
        exchange.getResponseBody.write(bytes)
        exchange.getResponseBody.flush()
        Thread.sleep(reply.stall.toMillis)
      else
        exchange.sendResponseHeaders(reply.status, if bytes.isEmpty then -1 else bytes.length)
        if bytes.nonEmpty then exchange.getResponseBody.write(bytes)
    catch case _: java.io.IOException => ()
    finally exchange.close()
  })
  server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
  server.start()

  val url = s"http://127.0.0.1:${server.getAddress.getPort}"

  /** `f(n)` answers the n-th request (1-based). */
  def respond(f: Int => Reply): Unit =
    received.clear()
    script = (_, n) => f(n)

  /** `f(request)` answers each request from its own content; safe when requests overlap. */
  def respondTo(f: Received => Reply): Unit =
    received.clear()
    script = (r, _) => f(r)

  def requests: List[Received] = received.asScala.toList
  def stop(): Unit = server.stop(0)

class ClientSuite extends munit.FunSuite:
  private val api = MockApi()
  import api.Reply

  override def afterAll(): Unit = api.stop()

  private val okBody =
    """{"model":"jev-latest","answers":{
      |"department":{"type":"choice","choice":"technical","probabilities":{"billing":0.159,"technical":0.84,"sales":0.001},"confidence":0.596},
      |"frustration":{"type":"score","score":1.035,"legend":{"0":"Calm","1":"Frustrated","2":"Very angry"},"probabilities":{"0":0.1,"1":0.76,"2":0.14},"confidence":0.842},
      |"is_urgent":{"type":"noul","noul":0.999}},
      |"usage":{"input_tokens":312,"output_tokens":48}}""".stripMargin

  private val department = Choice(
    "Which team should handle this",
    "billing" -> "Payment or subscription issues",
    "technical" -> "Bugs or integration problems",
    "sales" -> "Pricing or account questions"
  ).named("department")
  private val frustration = Score("How frustrated", "Calm", "Frustrated", "Very angry").named("frustration")
  private val urgent = Noul("The message conveys urgency").named("is_urgent")
  private val questions = Questions.of(department, frustration, urgent)

  private val fastRetry = RetryPolicy(backoffInitial = 1.milli, backoffMax = 5.millis)
  private def client(retry: RetryPolicy = fastRetry) =
    TypeSafeClient(ClientConfig(apiKey = Some("sk-test"), baseUrl = Some(api.url + "/"), retry = retry, env = _ => None))

  test("round trip: wire format, headers and typed answers") {
    api.respond(_ => Reply(200, okBody, Map("x-typesafe-request-id" -> "req_123")))
    val res = client().systemOne("Stripe keeps failing. ASAP.", questions)

    assertEquals(res(department).choice, "technical")
    assertEquals(res(department).as(_.capitalize), Right("Technical"))
    assertEquals(res(frustration).mostLikelyLevel, Some(1))
    assert(res(urgent).isYes(0.9))
    assertEquals(res.get(Noul().named("department")), None)
    assertEquals(res.requestId, Some("req_123"))
    assertEquals(res.meta.attempts, 1)
    assertEquals(res.usage.outputTokens, Some(48L))

    val r = api.requests.head
    assertEquals(r.method, "POST")
    assertEquals(r.path, "/v1/systemone")
    assertEquals(r.header("authorization"), Some("Bearer sk-test"))
    assertEquals(r.header("content-type"), Some("application/json"))
    assertEquals(r.header("accept"), Some("application/json"))
    assertEquals(r.header("x-typesafe-sdk"), Some(s"typesafe-sdk-scala/${Constants.Version}"))
    assertEquals(r.header("x-typesafe-retry-count"), None)
    assertEquals(
      Json.unsafeParse(r.body),
      Json.Obj(VectorMap(
        "state" -> Json.Str("Stripe keeps failing. ASAP."),
        "model" -> Json.Str("jev-latest"),
        "questions" -> questions.toJson
      ))
    )
  }

  test("structured state, per-call overrides and protected headers") {
    api.respond(_ => Reply(200, okBody))
    val opts = CallOptions(
      model = Some("jev-2"),
      timeout = Some(3.seconds),
      headers = Map("Authorization" -> "Bearer hijack", "X-Tenant" -> "acme", "X-TypeSafe-Retry-Count" -> "9"),
      extraBody = VectorMap("metadata" -> Json.obj("trace" -> "t1"))
    )
    client().systemOne(Ticket("Payouts", Some(2), List("a"), Channel.Email), questions, opts)
    val r = api.requests.head
    val body = Json.unsafeParse(r.body)
    assertEquals(body.get("state").map(_.render), Some("""{"subject":"Payouts","priority":2,"tags":["a"],"channel":"Email"}"""))
    assertEquals(body.get("model"), Some(Json.Str("jev-2")))
    assertEquals(body.get("metadata"), Some(Json.obj("trace" -> "t1")))
    assertEquals(r.header("authorization"), Some("Bearer sk-test"))
    assertEquals(r.header("x-tenant"), Some("acme"))
    assertEquals(r.header("x-typesafe-retry-count"), None)
  }

  test("goes through an AI gateway: a path of its own, and a key of its own") {
    api.respond(_ => Reply(200, okBody))
    val gateway = TypeSafeClient(
      ClientConfig(
        apiKey = Some("sk-test"),
        baseUrl = Some(api.url + "/v1/acct/gw/typesafe/"),
        headers = Map("cf-aig-authorization" -> "Bearer gw-key"),
        env = _ => None
      )
    )
    gateway.systemOne("x", questions)
    val r = api.requests.head
    assertEquals(r.path, "/v1/acct/gw/typesafe/v1/systemone")
    assertEquals(r.header("cf-aig-authorization"), Some("Bearer gw-key"))
    assertEquals(r.header("authorization"), Some("Bearer sk-test"))
  }

  test("429 with retry-after-ms is retried, then succeeds") {
    api.respond {
      case 1 => Reply(429, """{"error":{"message":"slow down"}}""", Map("retry-after-ms" -> "20"))
      case _ => Reply(200, okBody)
    }
    val t0 = System.nanoTime()
    val res = client().systemOne("x", questions)
    assert((System.nanoTime() - t0).nanos >= 20.millis)
    assertEquals(res.meta.attempts, 2)
    assertEquals(api.requests(1).header("x-typesafe-retry-count"), Some("1"))
  }

  test("529 exhausts retries") {
    api.respond(_ => Reply(529, "Overloaded"))
    val e = intercept[ApiException](client().systemOne("x", questions))
    assertEquals(api.requests.size, 3)
    assertEquals(e.kind, ApiErrorKind.InternalServer)
    assertEquals(e.detail, "Overloaded")
    assert(e.getMessage.endsWith("/v1/systemone: 529 Overloaded"), e.getMessage)
  }

  test("422 is not retried and the message is extracted") {
    api.respond(_ => Reply(422,
      """{"detail":[{"loc":["body","questions","frustration","criteria"],"msg":"List should have at least 2 items","type":"too_short"}]}""",
      Map("x-typesafe-request-id" -> "req_9")))
    val e = intercept[ApiException](client().systemOne("x", questions))
    assertEquals(api.requests.size, 1)
    assertEquals(e.kind, ApiErrorKind.UnprocessableEntity)
    assertEquals(e.detail, "questions.frustration.criteria: List should have at least 2 items")
    assertEquals(e.requestId, Some("req_9"))
  }

  test("retry-after beyond the budget stops immediately") {
    api.respond(_ => Reply(429, headers = Map("Retry-After" -> "60")))
    val e = intercept[ApiException](client().systemOne("x", questions))
    assertEquals(api.requests.size, 1)
    assertEquals(e.retryAfter, Some(60.seconds))
    assertEquals(e.detail, "status code (no body)")
  }

  test("custom predicate") {
    api.respond(_ => Reply(409))
    val policy = RetryPolicy(maxRetries = 1, backoffInitial = Duration.Zero).retryIf {
      case a: ApiException => a.status == 409
      case _               => false
    }
    val e = intercept[ApiException](client(policy).systemOne("x", questions))
    assertEquals(api.requests.size, 2)
    assertEquals(e.kind, ApiErrorKind.Other)
  }

  test("timeouts") {
    api.respond(_ => Reply(200, okBody, delay = 500.millis))
    val e = intercept[TimeoutException](
      client(RetryPolicy.none).systemOne("x", questions, CallOptions(timeout = Some(50.millis)))
    )
    assertEquals(e.timeout, 50.millis)
  }

  test("a body that stalls after the headers also times out") {
    api.respond(_ => Reply(200, okBody.take(20), stall = 3.seconds))
    val t0 = System.nanoTime()
    val e = intercept[TimeoutException](
      client(RetryPolicy.none).systemOne("x", questions, CallOptions(timeout = Some(100.millis)))
    )
    assert((System.nanoTime() - t0).nanos < 2.seconds)
    assertEquals(e.timeout, 100.millis)
  }

  test("connection errors are retried then surface") {
    val port = { val s = java.net.ServerSocket(0); try s.getLocalPort finally s.close() }
    val c = TypeSafeClient(ClientConfig(apiKey = Some("k"), baseUrl = Some(s"http://127.0.0.1:$port"), retry = fastRetry, env = _ => None))
    intercept[ConnectionException](c.systemOne("x", questions))
  }

  test("invalid success body reports the field path") {
    api.respond(_ => Reply(200, okBody.replace(""","confidence":0.596""", "")))
    val e = intercept[ResponseValidationException](client().systemOne("x", questions))
    assertEquals(e.fieldPath, "answers.department.confidence")
    assertEquals(api.requests.size, 1)
  }

  test("local validation happens before sending") {
    api.respond(_ => Reply(200, okBody))
    intercept[InvalidRequestException](client().systemOne("x", Questions.empty))
    intercept[InvalidRequestException](client().systemOne(Double.NaN, questions))
    assertEquals(api.requests.size, 0)
  }

  test("async and Future variants") {
    api.respond(_ => Reply(200, okBody))
    val a = client().systemOneAsync("x", questions).get()
    val f = Await.result(client().systemOneFuture("x", questions), 5.seconds)
    assertEquals(a.answers, f.answers)
    api.respond(_ => Reply(401, """{"detail":"Invalid API key"}"""))
    val err = Await.ready(client().systemOneFuture("x", questions), 5.seconds).value.get.failed.get
    assertEquals(err.asInstanceOf[ApiException].kind, ApiErrorKind.Authentication)
  }

  test("cancelling the returned future aborts the call and stops retrying") {
    api.respond(_ => Reply(503, delay = 200.millis)) // retryable: without cancellation this loops
    val f = client().systemOneAsync("x", questions)
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while api.requests.isEmpty && System.nanoTime() < deadline do Thread.sleep(5)
    assert(f.cancel(true))
    Thread.sleep(700)
    assert(f.isCancelled)
    assertEquals(api.requests.size, 1)
  }

  test("list models") {
    api.respond(_ => Reply(200, """{"models":[{"name":"jev-latest","description":"Flagship","release_date":"2026-05-01"}]}"""))
    val res = client().models.list()
    assertEquals(res.models, Vector(ModelMetadata("jev-latest", "Flagship", "2026-05-01")))
    val r = api.requests.head
    assertEquals((r.method, r.path), ("GET", "/v1/models"))
    assertEquals(r.header("content-type"), None)
  }

  test("the Either variants put the SDK's own failures in the value") {
    api.respond(_ => Reply(200, okBody))
    assertEquals(client().systemOneEither("x", questions).map(_(urgent).noul), Right(0.999))

    api.respond(_ => Reply(429, """{"detail":"slow down"}"""))
    client(RetryPolicy.none).systemOneEither("x", questions) match
      case Left(ApiException(429, ApiErrorKind.RateLimit)) => ()
      case other                                            => fail(s"expected a 429 in the Left, got $other")

    api.respond(_ => Reply(401, """{"detail":"Invalid API key"}"""))
    assert(client().models.listEither().left.exists(_.isInstanceOf[ApiException]))
    api.respond(_ => Reply(200, """{"models":[]}"""))
    assertEquals(client().modelsEither().map(_.models), Right(Vector.empty))

    // Rejected before sending: a failure of the SDK's own, so a Left too.
    assert(client().systemOneEither("x", Questions.empty).left.exists(_.isInstanceOf[InvalidRequestException]))
  }

  test("an interrupt is still an exception, not a Left") {
    api.respond(_ => Reply(200, okBody, delay = 2.seconds))
    Thread.currentThread().interrupt()
    // Not `intercept`: it only catches non-fatal throwables, and an interrupt is not one.
    val outcome =
      try Right(client().systemOneEither("x", questions))
      catch case _: InterruptedException => Left(Thread.currentThread().isInterrupted) // re-armed?
      finally Thread.interrupted() // leave the flag clear for the next test
    assertEquals(outcome, Left(true))
  }

  test("TypeSafeClient.either returns a configuration problem instead of throwing it") {
    val noEnv: String => Option[String] = _ => None
    assert(TypeSafeClient.either(ClientConfig(env = noEnv)).left.exists(_.getMessage.contains("No API key")))
    val ok = TypeSafeClient.either(ClientConfig(apiKey = Some("sk-test"), baseUrl = Some(api.url), env = noEnv))
    assertEquals(ok.map(_.baseUrl), Right(api.url))
    ok.foreach(_.close())
  }

  test("the client is AutoCloseable") {
    val closed = scala.util.Using(client())(_.defaultModel)
    assertEquals(closed.toOption, Some("jev-latest"))
  }
