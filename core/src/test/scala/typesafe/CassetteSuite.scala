package typesafe

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.immutable.VectorMap
import scala.concurrent.Await
import scala.concurrent.duration.*

class CassetteSuite extends munit.FunSuite:
  private val api = MockApi()
  import api.Reply

  override def afterAll(): Unit = api.stop()

  private val body =
    """{ "model": "jev-latest",
      |  "answers": {"is_urgent": {"type": "noul", "noul": 0.97}},
      |  "usage": {"input_tokens": 20, "output_tokens": 3} }""".stripMargin
  private val urgent = Noul("The message conveys urgency").named("is_urgent")
  private val questions = Questions.of(urgent)
  private val state = "The payout failed again."

  private def tempDir(): Path = Files.createTempDirectory("typesafe-cassette")

  private def client(record: Option[Path] = None, replay: Option[Path] = None, apiKey: Option[String] = Some("sk-test")) =
    TypeSafeClient(
      ClientConfig(apiKey = apiKey, baseUrl = Some(api.url), retry = RetryPolicy.none, env = _ => None, record = record, replay = replay)
    )

  test("the key is the hash of the compact body, the same digest the Rust and TypeScript ports pin") {
    val key = Cassette.key(state, "jev-latest", questions)
    assertEquals(key, "4bb6a561cd7ce28500dc6aa8fc821771e45e4f811f1195c2441263651a7dca55")
    assertEquals(
      key,
      Cassette.keyOf(
        """{"state":"The payout failed again.","model":"jev-latest","questions":{"is_urgent":{"type":"noul","instructions":"The message conveys urgency"}}}"""
      )
    )
  }

  test("the key follows question order and every field") {
    val a = Questions("x" -> Noul("x"), "y" -> Score("y", "lo", "hi"))
    val b = Questions("y" -> Score("y", "lo", "hi"), "x" -> Noul("x"))
    assertNotEquals(Cassette.key("s", "m", a), Cassette.key("s", "m", b))
    assertNotEquals(Cassette.key("s", "m", a), Cassette.key("s", "n", a))
    assertNotEquals(Cassette.key("s", "m", a), Cassette.key("t", "m", a))
  }

  test("recordings are compact, keep server order and spell numbers as serde_json does") {
    val dir = tempDir()
    val raw = Json.unsafeParse(
      """{ "model": "m",
        |  "answers": {"z": {"type": "noul", "noul": 0.5}, "a": {"type": "noul", "noul": 1e-5}},
        |  "usage": {} }""".stripMargin
    )
    Cassette.write(dir, "k", raw)
    assertEquals(
      Files.readString(Cassette.path(dir, "k"), UTF_8),
      "{\"model\":\"m\",\"answers\":{\"z\":{\"type\":\"noul\",\"noul\":0.5},\"a\":{\"type\":\"noul\",\"noul\":0.00001}},\"usage\":{}}\n"
    )
    val spelled = List("1", "-3", "1.0", "1e2", "0.1", "1e-7", "1.5e20", "123456789012345678", "0.000123", "2.5e-6", "1e16", "1e15")
      .map(s => Cassette.number(BigDecimal(s)))
    assertEquals(
      spelled,
      List("1", "-3", "1.0", "100.0", "0.1", "1e-7", "1.5e20", "123456789012345678", "0.000123", "2.5e-6", "1e16", "1000000000000000.0")
    )
  }

  test("a recording client keeps each successful response, and a replaying one answers from it offline") {
    val dir = tempDir()
    api.respond(_ => Reply(200, body))
    val live = client(record = Some(dir)).systemOne(state, questions)
    assertEquals(live(urgent).noul, 0.97)
    val key = Cassette.key(state, "jev-latest", questions)
    assert(Files.exists(Cassette.path(dir, key)))
    assertEquals(api.requests.size, 1)

    api.respond(_ => Reply(500))
    val replaying = client(replay = Some(dir), apiKey = None)
    val again = replaying.systemOne(state, questions)
    assertEquals(again(urgent).noul, 0.97)
    assertEquals(again.meta.attempts, 0)
    assertEquals(again.meta.headers, Map.empty[String, List[String]])
    assertEquals(again.usage.inputTokens, Some(20L))
    assertEquals(api.requests.size, 0)

    assertEquals(Await.result(replaying.systemOneFuture(state, questions), 5.seconds)(urgent).noul, 0.97)
  }

  test("a request that was never recorded is a ReplayMissException, and nothing is sent") {
    val dir = tempDir()
    api.respond(_ => Reply(200, body))
    val e = intercept[ReplayMissException](client(replay = Some(dir)).systemOne(state, questions))
    assertEquals(e.key, Cassette.key(state, "jev-latest", questions))
    assertEquals(e.path, Cassette.path(dir, e.key))
    assertEquals(api.requests.size, 0)
  }

  test("extraBody is part of the key") {
    val dir = tempDir()
    api.respond(_ => Reply(200, body))
    val opts = CallOptions(extraBody = VectorMap("metadata" -> Json.obj("trace" -> "t1")))
    client(record = Some(dir)).systemOne(state, questions, opts)
    val replaying = client(replay = Some(dir))
    replaying.systemOne(state, questions, opts)
    intercept[ReplayMissException](replaying.systemOne(state, questions))
  }

  test("only responses that decode are recorded; failures are not") {
    val dir = tempDir()
    api.respond(_ => Reply(200, """{"model":"m","answers":{}}"""))
    intercept[ResponseValidationException](client(record = Some(dir)).systemOne(state, questions))
    api.respond(_ => Reply(400, """{"detail":"nope"}"""))
    intercept[ApiException](client(record = Some(dir)).systemOne(state, questions))
    assertEquals(Files.list(dir).count(), 0L)
  }

  test("a recording that no longer decodes is a ResponseValidationException") {
    val dir = tempDir()
    val key = Cassette.key(state, "jev-latest", questions)
    Files.writeString(Cassette.path(dir, key), "not json")
    intercept[ResponseValidationException](client(replay = Some(dir)).systemOne(state, questions))
    Files.writeString(Cassette.path(dir, key), """{"model":"m"}""")
    val e = intercept[ResponseValidationException](client(replay = Some(dir)).systemOne(state, questions))
    assertEquals(e.fieldPath, "usage")
  }

  test("configuration: both set, the environment, the key, and listing models") {
    val dir = tempDir()
    intercept[ConfigException](client(record = Some(dir), replay = Some(dir)))
    intercept[ConfigException](client(apiKey = None))
    val env = Map(Constants.ReplayEnv -> dir.toString)
    val fromEnv = TypeSafeClient(ClientConfig(baseUrl = Some(api.url), env = env.get))
    intercept[ReplayMissException](fromEnv.systemOne(state, questions))
    intercept[ConfigException](fromEnv.models.list())
    val both = Map(Constants.ReplayEnv -> dir.toString, Constants.RecordEnv -> dir.toString, Constants.ApiKeyEnv -> "k")
    intercept[ConfigException](TypeSafeClient(ClientConfig(env = both.get)))
    val blank = Map(Constants.ReplayEnv -> "  ", Constants.ApiKeyEnv -> "k")
    TypeSafeClient(ClientConfig(env = blank.get))
    val nested = dir.resolve("a/b")
    client(record = Some(nested))
    assert(Files.isDirectory(nested))
  }
