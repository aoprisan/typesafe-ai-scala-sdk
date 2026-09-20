package examples

import scala.concurrent.duration.*
import typesafe.*

/** What failure looks like, and what the retry policy does about it.
  *
  * This one always runs against a local fake, because the interesting part is making the server
  * misbehave on demand. It is also the pattern for your own tests: point `baseUrl` at a server you
  * control and every branch below becomes assertable.
  *
  * {{{
  * sbt "examples/runMain examples.errorsAndRetries"
  * }}}
  */
@main def errorsAndRetries(): Unit =
  val urgent = Noul("The message conveys urgency").named("is_urgent")
  val questions = Questions.of(urgent)
  val ticket = "My payouts have failed for 3 days!"

  /** A client pointed at `api`, with a policy that does not make the example slow. */
  def clientFor(api: FakeApi, retry: RetryPolicy = RetryPolicy(backoffInitial = 50.millis, backoffMax = 200.millis)) =
    TypeSafeClient(ClientConfig(apiKey = Some("fake-key"), baseUrl = Some(api.baseUrl), retry = retry))

  def withApi[A](api: FakeApi)(body: TypeSafeClient => A): A =
    val client = clientFor(api)
    try body(client)
    finally
      client.close()
      api.stop()

  // 1. Two failures, then an answer. The policy rides it out; `meta.attempts` says what it cost.
  withApi(FakeApi.start(failFirst = 2)) { client =>
    val res = client.systemOne(ticket, questions)
    println(s"retried   → answered on attempt ${res.meta.attempts} (${res(urgent).noul})")
  }

  // 2. A server that never recovers. The last failure is raised, classified.
  withApi(FakeApi.replying((_, _) => (429, """{"error":{"message":"Rate limit exceeded"}}"""))) { client =>
    try client.systemOne(ticket, questions)
    catch
      case e: ApiException =>
        println(s"gave up   → ${e.status} ${e.kind}, retry-after ${e.retryAfter.getOrElse("unset")}, " +
          s"request ${e.requestId.getOrElse("-")}")
        println(s"            detail: ${e.detail}")
  }

  // 3. A FastAPI-style validation body is flattened into one readable message, path included.
  withApi(FakeApi.replying((_, _) =>
    (422, """{"detail":[{"loc":["body","questions","frustration","criteria"],
             |"msg":"List should have at least 2 items"}]}""".stripMargin)
  )) { client =>
    try client.systemOne(ticket, questions)
    catch case e: ApiException => println(s"rejected  → ${e.kind}: ${e.detail}")
  }

  // 4. A 2xx whose body is not what the contract promises. `fieldPath` says where it broke, and
  //    the body you got is kept for the log.
  withApi(FakeApi.replying((_, _) =>
    (200, """{"model":"jev-latest","usage":{},"answers":{"is_urgent":{"type":"noul","noul":"very"}}}""")
  )) { client =>
    try client.systemOne(ticket, questions)
    catch case e: ResponseValidationException => println(s"malformed → ${e.fieldPath}: ${e.detail}")
  }

  // 5. Some failures never reach the network at all.
  withApi(FakeApi.start()) { client =>
    try client.systemOne(ticket, Questions.empty)
    catch case e: InvalidRequestException => println(s"local     → ${e.getMessage}")
  }

  // 6. The built-in policy retries 408, 429 and 5xx. Anything else is a decision only you can make,
  //    so `retryIf` is where you make it — here, a 409 that the API documents as worth repeating.
  val conflicts = FakeApi.replying { (n, _) =>
    if n <= 1 then (409, """{"error":{"message":"Concurrent update, try again"}}""")
    else (200, """{"model":"jev-latest","usage":{},"answers":{"is_urgent":{"type":"noul","noul":0.91}}}""")
  }
  val custom = RetryPolicy(maxRetries = 3, backoffInitial = 50.millis, budget = Some(5.seconds))
    .retryIf {
      case e: ApiException => e.status == 409
      case _               => false
    }
  val client = clientFor(conflicts, custom)
  try
    val res = client.systemOne(ticket, questions)
    println(s"retryIf   → ${res(urgent).noul} after ${conflicts.requests} requests")
  finally
    client.close()
    conflicts.stop()
