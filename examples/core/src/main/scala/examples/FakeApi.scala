package examples

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable.VectorMap
import scala.concurrent.duration.*
import typesafe.Json

/** A local stand-in for the System One API, so every example in this folder runs without a key.
  *
  * It is also the shape of a test double for your own suites: the client takes a `baseUrl`, so
  * anything that speaks the wire format — a JDK `HttpServer` like this one, WireMock, a container —
  * can stand in for the real thing without touching the code under test.
  *
  * The answers are canned but deterministic: the same question name always gets the same numbers,
  * so an example's output does not change between runs.
  */
final class FakeApi private (server: HttpServer, seen: AtomicInteger):

  /** Point a `ClientConfig(baseUrl = Some(...))` here. */
  def baseUrl: String = s"http://127.0.0.1:${server.getAddress.getPort}"

  /** How many requests have arrived, retries included. */
  def requests: Int = seen.get()

  def stop(): Unit = server.stop(0)

object FakeApi:

  /** Canned answers, after `latency`. The first `failFirst` requests get a `503` instead, which is
    * what the retry policy is there for.
    */
  def start(failFirst: Int = 0, latency: FiniteDuration = 40.millis): FakeApi =
    replying { (n, request) =>
      Thread.sleep(latency.toMillis)
      if n <= failFirst then (503, """{"error":{"message":"Overloaded, please retry"}}""")
      else (200, answerFor(request))
    }

  /** Full control: `reply(n, requestBody)` answers the n-th request with a status and a body. */
  def replying(reply: (Int, String) => (Int, String)): FakeApi =
    val seen = AtomicInteger(0)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    // A pool, not the default single thread: the concurrency examples are only interesting if the
    // fake can actually answer several calls at once. Daemon threads so the JVM can still exit.
    server.setExecutor(Executors.newFixedThreadPool(8, (r: Runnable) =>
      val t = Thread(r, "fake-api")
      t.setDaemon(true)
      t
    ))
    server.createContext("/v1/systemone", (exchange: HttpExchange) =>
      val n = seen.incrementAndGet()
      val body = String(exchange.getRequestBody.readAllBytes(), UTF_8)
      val (status, response) = reply(n, body)
      send(exchange, n, status, response)
    )
    server.createContext("/v1/models", (exchange: HttpExchange) =>
      send(exchange, seen.incrementAndGet(), 200, models)
    )
    server.start()
    new FakeApi(server, seen)

  private val models =
    """{"models":[
      |{"name":"jev-latest","description":"Flagship model","release_date":"2026-05-01"},
      |{"name":"jev-mini","description":"Cheaper and faster","release_date":"2026-05-01"}]}""".stripMargin

  private def send(exchange: HttpExchange, n: Int, status: Int, body: String): Unit =
    val bytes = body.getBytes(UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.getResponseHeaders.add("x-typesafe-request-id", f"req_fake_$n%04d")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    exchange.getResponseBody.write(bytes)
    exchange.close()

  /** Answers every question in the request, in the type it asked for. Numbers depend on the state
    * as well as on the question, so a batch of different tickets does not come back identical.
    */
  private def answerFor(request: String): String =
    val req = Json.parse(request).getOrElse(Json.Null)
    val questions = req.get("questions").flatMap(_.asObject).getOrElse(VectorMap.empty)
    val state = req.get("state").map(_.render).getOrElse("")
    Json.Obj(
      VectorMap(
        "model" -> Json.Str(req.get("model").flatMap(_.asString).getOrElse("jev-latest")),
        "answers" -> Json.Obj(questions.map((name, q) => name -> answer(s"$state/$name", q))),
        "usage" -> Json.Obj(VectorMap("input_tokens" -> num(312), "output_tokens" -> num(48)))
      )
    ).render

  private def answer(seed: String, question: Json): Json =
    question.get("type").flatMap(_.asString) match
      case Some("choice") =>
        val labels = question.get("criteria").flatMap(_.asObject).map(_.keys.toVector).getOrElse(Vector("yes", "no"))
        val weights = normalised(labels.map(l => seeded(s"$seed/$l")))
        val probabilities = labels.zip(weights)
        Json.Obj(
          VectorMap(
            "type" -> Json.Str("choice"),
            "choice" -> Json.Str(probabilities.maxBy(_._2)._1),
            "probabilities" -> Json.Obj(VectorMap.from(probabilities.map((l, p) => l -> num(p)))),
            "confidence" -> num(probabilities.map(_._2).max)
          )
        )
      case Some("score") =>
        val levels = question.get("criteria").flatMap(_.asArray).getOrElse(Vector.empty)
        val weights = normalised(levels.indices.toVector.map(i => seeded(s"$seed/$i")))
        Json.Obj(
          VectorMap(
            "type" -> Json.Str("score"),
            "score" -> num(round(weights.zipWithIndex.map((p, i) => p * i).sum)),
            "confidence" -> num(if weights.isEmpty then 0.0 else weights.max),
            "legend" -> Json.Obj(VectorMap.from(levels.zipWithIndex.map((l, i) => i.toString -> l))),
            "probabilities" -> Json.Obj(VectorMap.from(weights.zipWithIndex.map((p, i) => i.toString -> num(p))))
          )
        )
      case _ =>
        Json.Obj(VectorMap("type" -> Json.Str("noul"), "noul" -> num(seeded(seed))))

  /** A stable pseudo-probability for a seed, so runs are reproducible. */
  private def seeded(seed: String): Double =
    (math.abs(scala.util.hashing.MurmurHash3.stringHash(seed)) % 1000) / 1000.0

  private def normalised(weights: Vector[Double]): Vector[Double] =
    val total = weights.sum
    if total <= 0 then weights.map(_ => round(1.0 / weights.size.max(1)))
    else weights.map(w => round(w / total))

  private def round(d: Double): Double = math.round(d * 1000) / 1000.0

  private def num(d: Double): Json = Json.Num(BigDecimal(d))
