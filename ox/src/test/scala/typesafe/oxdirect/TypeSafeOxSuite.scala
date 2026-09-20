package typesafe.oxdirect

import java.net.http.HttpClient
import ox.{fork, supervised}
import ox.flow.Flow
import scala.concurrent.duration.*
import typesafe.*

class TypeSafeOxSuite extends munit.FunSuite:
  private val api = MockApi()
  import api.{Received, Reply}

  override def afterAll(): Unit = api.stop()

  private val urgent = Noul("The message conveys urgency").named("is_urgent")
  private val questions = Questions.of(urgent)
  private val states = (0 until 6).map(i => s"ticket-$i").toVector

  private val okBody =
    """{"model":"jev-latest","answers":{"is_urgent":{"type":"noul","noul":0.9}},
      |"usage":{"input_tokens":12,"output_tokens":3}}""".stripMargin

  private def config = ClientConfig(
    apiKey = Some("sk-test"),
    baseUrl = Some(api.url),
    retry = RetryPolicy(maxRetries = 0),
    env = _ => None
  )

  /** The state a request carries, e.g. `ticket-3` -> 3. */
  private def indexOf(request: Received): Int =
    Json.unsafeParse(request.body).get("state").flatMap(_.asString).map(_.stripPrefix("ticket-").toInt).get

  private def answer(index: Int): String =
    s"""{"model":"jev-latest","answers":{"is_urgent":{"type":"noul","noul":0.$index}},"usage":{"input_tokens":1,"output_tokens":1}}"""

  /** Spins until `cond`, or gives up, so a broken test fails instead of hanging the suite. */
  private def await(what: String)(cond: => Boolean): Unit =
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while !cond do
      assert(System.nanoTime() < deadline, s"timed out waiting for $what")
      Thread.sleep(5)

  test("the core's blocking call is the Ox call: it just works inside a scope") {
    api.respond(_ => Reply(200, okBody))
    val noul = supervised {
      val client = TypeSafeOx.inScope(config)
      // bound to a local: `systemOne(..)(urgent)` would read `urgent` as the implicit ToJson
      val res = client.systemOne("Stripe keeps failing.", questions)
      res(urgent).noul
    }
    assertEquals(noul, 0.9)
  }

  test("forks run the calls concurrently") {
    api.respondTo(r => Reply(200, answer(indexOf(r)), delay = 150.millis))
    val started = System.nanoTime()
    val nouls = supervised {
      val client = TypeSafeOx.inScope(config)
      val forks = states.map { state =>
        fork {
          val res = client.systemOne(state, questions)
          res(urgent).noul
        }
      }
      forks.map(_.join())
    }
    val elapsed = (System.nanoTime() - started).nanos
    assertEquals(nouls, states.indices.map(i => s"0.$i".toDouble).toVector)
    assert(elapsed < 600.millis, s"six 150ms calls in parallel should not have taken $elapsed")
  }

  test("inScope closes the client when the scope ends") {
    api.respond(_ => Reply(200, okBody))
    val http = HttpClient.newHttpClient()
    supervised {
      val client = TypeSafeOx.inScope(config.copy(httpClient = Some(http)))
      client.systemOne("x", questions)
      ()
    }
    assert(http.isTerminated, "leaving the scope should have closed the HTTP client")
  }

  test("a fork abandoned when its scope ends stops the call in flight") {
    // The whole claim of this module: the core's blocking `await` catches the interrupt, cancels the
    // exchange and re-arms the flag, which is exactly what a supervised scope needs on the way out.
    // Without that, leaving the scope would block for the full five seconds of the stall.
    api.respond(_ => Reply(200, okBody, stall = 5.seconds))
    val client = TypeSafeClient(config)
    try
      val started = System.nanoTime()
      supervised {
        fork(client.systemOne("x", questions))
        await("the request to reach the server")(api.requests.nonEmpty)
      }
      val elapsed = (System.nanoTime() - started).nanos
      assert(elapsed < 3.seconds, s"leaving the scope should have interrupted the call, took $elapsed")
    finally client.close()
  }

  test("systemOneEither puts the SDK's own failure in the value") {
    api.respond(_ => Reply(401, """{"error":{"message":"bad key"}}"""))
    supervised {
      TypeSafeOx.inScope(config).systemOneEither("x", questions) match
        case Left(e: ApiException) => assertEquals(e.status, 401)
        case other                 => fail(s"expected a Left(ApiException), got $other")
    }
  }

  test("the Left is the sealed hierarchy, so matching it needs no catch-all") {
    api.respond(_ => Reply(400, """{"error":{"message":"nope"}}"""))
    val described = supervised {
      // No `case _ =>` on purpose: this compiles only while the failure type stays sealed and narrow.
      TypeSafeOx.inScope(config).systemOneEither("x", questions) match
        case Right(_)                             => "ok"
        case Left(_: ConfigException)             => "config"
        case Left(_: InvalidRequestException)     => "request"
        case Left(_: ApiException)                => "api"
        case Left(_: ConnectionException)         => "connection"
        case Left(_: TimeoutException)            => "timeout"
        case Left(_: ResponseValidationException) => "response"
    }
    assertEquals(described, "api")
  }

  test("modelsEither narrows the same way") {
    api.respond(_ => Reply(200, """{"models":[{"name":"jev-1","description":"d","release_date":"2025-01-01"}]}"""))
    supervised {
      TypeSafeOx.inScope(config).modelsEither() match
        case Right(res) => assertEquals(res.models.map(_.name), Vector("jev-1"))
        case other      => fail(s"expected a Right, got $other")
    }
  }

  test("systemOnePar keeps input order however the answers arrive") {
    // Later tickets answer sooner, so completion order is the reverse of input order.
    api.respondTo { r =>
      val i = indexOf(r)
      Reply(200, answer(i), delay = (states.size - i) * 60.millis)
    }
    val nouls = supervised {
      val client = TypeSafeOx.inScope(config)
      Flow.fromIterable(states).systemOnePar(client, questions, parallelism = states.size).runToList()
    }
    assertEquals(nouls.map(_(urgent).noul), states.indices.map(i => s"0.$i".toDouble).toList)
  }

  test("systemOneParUnordered emits as answers arrive") {
    api.respondTo { r =>
      val i = indexOf(r)
      Reply(200, answer(i), delay = (states.size - i) * 60.millis)
    }
    val nouls = supervised {
      val client = TypeSafeOx.inScope(config)
      Flow.fromIterable(states).systemOneParUnordered(client, questions, parallelism = states.size).runToList()
    }.map(_(urgent).noul)
    assertEquals(nouls.sorted, states.indices.map(i => s"0.$i".toDouble).toList)
    assertNotEquals(nouls, nouls.sorted, "answers should not have been re-ordered back into input order")
  }

  test("systemOneParEither pairs each state with a typed outcome and keeps the flow alive") {
    api.respondTo { r =>
      val i = indexOf(r)
      if i % 2 == 0 then Reply(200, answer(i)) else Reply(400, """{"error":{"message":"nope"}}""")
    }
    val got = supervised {
      val client = TypeSafeOx.inScope(config)
      Flow.fromIterable(states).systemOneParEither(client, questions, parallelism = 3).runToList()
    }
    assertEquals(got.map(_._1), states.toList)
    got.zipWithIndex.foreach {
      case ((_, Right(res)), i) if i % 2 == 0 => assertEquals(res(urgent).noul, s"0.$i".toDouble)
      case ((_, Left(e: ApiException)), i)    => assertEquals(e.status, 400, s"ticket $i")
      case (other, i)                         => fail(s"unexpected outcome for ticket $i: $other")
    }
  }
