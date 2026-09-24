package typesafe

import scala.concurrent.duration.*

final case class Ticket(subject: String, priority: Option[Int], tags: List[String], channel: Channel) derives ToJson
enum Channel derives ToJson:
  case Email, Chat
enum Shape derives ToJson:
  case Circle(radius: Double)
  case Box(width: Int, height: Int, label: Option[String])
  case Dot

class UnitSuite extends munit.FunSuite:

  test("json round-trips and escapes") {
    val text = """{"a":[1,2.5,-3e2,true,null],"b":"q\"\\\n\u0001é","c":{}}"""
    val j = Json.unsafeParse(text)
    assertEquals(Json.unsafeParse(j.render), j)
    assertEquals(j.get("b").flatMap(_.asString), Some("q\"\\\n\u0001é"))
    assert(Json.parse("[1,]").isLeft)
    assert(Json.parse("{} x").isLeft)
    assert(Json.parse("\"\\u12\"").isLeft)
    assertEquals(Json.obj("n" -> 1, "s" -> "x", "o" -> Option.empty[Int], "l" -> List(1, 2)).render,
      """{"n":1,"s":"x","o":null,"l":[1,2]}""")
  }

  test("malformed numbers and deep nesting are parse errors, not exceptions") {
    assert(Json.parse("1e99999999999").left.exists(_.contains("out of range")))
    assert(Json.parse("[" * 100000).left.exists(_.contains("nesting")))
    assert(Json.parse("{\"a\":" * 100000).left.exists(_.contains("nesting")))
    assertEquals(Json.parse("[" * 512 + "]" * 512).map(_ => ()), Right(()))
  }

  test("derived ToJson for case classes and enums") {
    val t = Ticket("Payouts", None, List("stripe"), Channel.Chat)
    assertEquals(ToJson[Ticket](t).render, """{"subject":"Payouts","tags":["stripe"],"channel":"Chat"}""")
  }

  test("derived ToJson for enums with parameterized cases") {
    assertEquals(ToJson[Shape](Shape.Circle(1.5)).render, """{"radius":1.5}""")
    assertEquals(ToJson[Shape](Shape.Box(2, 3, None)).render, """{"width":2,"height":3}""")
    assertEquals(ToJson[Shape](Shape.Dot).render, "\"Dot\"")
    assertEquals(ToJson[List[Shape]](List(Shape.Dot, Shape.Circle(1))).render, """["Dot",{"radius":1.0}]""")
  }

  test("questions serialize like the API reference and keep order") {
    val q = Questions(
      "department" -> Choice("Which team should handle this", "billing" -> "Payment or subscription issues").label("other"),
      "frustration" -> Score("How frustrated", "Calm", "Angry"),
      "is_urgent" -> Noul("Urgent?").describeTrue("Explicitly time-sensitive"),
      "bare" -> Noul()
    )
    assertEquals(
      q.toJson.render,
      """{"department":{"type":"choice","instructions":"Which team should handle this","criteria":{"billing":"Payment or subscription issues","other":null}},""" +
        """"frustration":{"type":"score","instructions":"How frustrated","criteria":["Calm","Angry"]},""" +
        """"is_urgent":{"type":"noul","instructions":"Urgent?","criteria":{"true":"Explicitly time-sensitive"}},""" +
        """"bare":{"type":"noul"}}"""
    )
  }

  test("structured instructions and levels") {
    val s = Score(Json.obj("task" -> "rate", "focus" -> List("tone"))).level(Json.obj("level" -> "low")).level("high")
    assertEquals(s.toJson.render, """{"type":"score","instructions":{"task":"rate","focus":["tone"]},"criteria":[{"level":"low"},"high"]}""")
  }

  test("local validation mirrors the python sdk") {
    intercept[InvalidRequestException](Questions.empty.validate())
    intercept[InvalidRequestException](Questions("s" -> Score("x")).validate())
    val noOptions = intercept[InvalidRequestException](Questions("c" -> Choice.labels("x")).validate())
    assert(noOptions.getMessage.contains("at least one option is required"), noOptions.getMessage)
    intercept[InvalidRequestException](Questions("r" -> RawQuestion(Json.obj("type" -> "choice", "criteria" -> Json.obj()))).validate())
    intercept[InvalidRequestException](Questions("r" -> RawQuestion(Json.obj("instructions" -> "x"))).validate())
    intercept[InvalidRequestException](Questions("r" -> RawQuestion(Json.obj("type" -> ""))).validate())
    intercept[InvalidRequestException](Questions("r" -> RawQuestion(Json.obj("type" -> "choice"))).validate())
    intercept[InvalidRequestException](Questions("r" -> RawQuestion(Json.obj("type" -> "score", "criteria" -> List.empty[String]))).validate())
    Questions("r" -> RawQuestion(Json.obj("type" -> "noul", "future" -> 1))).validate()
  }

  private val sample = Json.unsafeParse(
    """{"model":"jev-latest","answers":{
      |"department":{"type":"choice","choice":"technical","probabilities":{"billing":0.159,"technical":0.84,"sales":0.001},"confidence":0.596},
      |"frustration":{"type":"score","score":1.6,"legend":{"0":"Calm","1":"Frustrated","2":"Very angry"},"probabilities":{"0":0.05,"1":0.3,"2":0.65},"confidence":0.78},
      |"is_urgent":{"type":"noul","noul":0.999},
      |"future":{"type":"span","start":3}},
      |"usage":{"input_tokens":312,"output_tokens":48,"extra":true}}""".stripMargin
  )

  test("decodes all answer types and skips unknown ones") {
    var skipped = List.empty[String]
    val (model, usage, answers) = Decode.systemOne(sample, (n, _) => skipped ::= n)
    assertEquals(model, "jev-latest")
    assertEquals(usage, Usage(Some(312), Some(48)))
    assertEquals(answers.keys.toList, List("department", "frustration", "is_urgent"))
    assertEquals(skipped, List("future"))
    val s = answers("frustration").asInstanceOf[ScoreAnswer]
    assertEquals(s.legend(2), Json.Str("Very angry"))
    assertEquals(s.mostLikelyLevel, Some(2))
    assertEquals(answers("department").asInstanceOf[ChoiceAnswer].ranked.head, "technical" -> 0.84)
  }

  test("a bad model entry is named by its index, as the Python SDK names it") {
    val j = Json.parse("""{"models":[{"name":"a","description":"","release_date":""},{}]}""").toOption.get
    val path = try { Decode.models(j); "" } catch case Decode.Failure(p, _) => p
    assertEquals(path, "models[1].name")
  }

  private def pathOf(j: Json): String =
    try { Decode.systemOne(j, (_, _) => ()); "" } catch case Decode.Failure(p, _) => p

  private def edit(path: List[String], f: Option[Json] => Option[Json])(j: Json): Json = (path, j) match
    case (k :: Nil, Json.Obj(o))  => Json.Obj(f(o.get(k)).fold(o - k)(v => o.updated(k, v)))
    case (k :: rest, Json.Obj(o)) => Json.Obj(o.updated(k, edit(rest, f)(o(k))))
    case _                        => j

  test("decode failures carry precise field paths") {
    assertEquals(pathOf(edit(List("answers", "department", "confidence"), _ => None)(sample)), "answers.department.confidence")
    assertEquals(pathOf(edit(List("answers", "frustration", "probabilities", "1"), _ => Some(Json.Str("hi")))(sample)), "answers.frustration.probabilities.1")
    assertEquals(pathOf(edit(List("answers", "is_urgent", "type"), _ => Some(Json.Num(7)))(sample)), "answers.is_urgent.type")
    assertEquals(pathOf(edit(List("usage"), _ => None)(sample)), "usage")
    assertEquals(pathOf(edit(List("usage"), _ => Some(Json.obj()))(sample)), "")
  }

  test("error message extraction") {
    def m(s: String) = ApiException.extractMessage(Json.unsafeParse(s))
    assertEquals(m("""{"detail":[{"loc":["body","questions","x","criteria"],"msg":"Field required"},{"loc":["body","model"],"msg":"Bad"}]}"""),
      Some("questions.x.criteria: Field required; model: Bad"))
    assertEquals(m("""{"error":"e","message":"m"}"""), Some("e"))
    assertEquals(m("""{"error":{"message":"em"}}"""), Some("em"))
    assertEquals(m("""{"message":"m","detail":"d"}"""), Some("m"))
    assertEquals(m("""{"detail":{"message":"dm"}}"""), Some("dm"))
    assertEquals(m("""{"other":1}"""), None)
    val long = ApiException(500, Some(Json.obj("x" -> ("y" * 500))), Map.empty, None)
    assertEquals(long.detail.length, 201)
    assertEquals(ApiException(503, None, Map.empty, Some("GET http://x/v1/models")).getMessage,
      "GET http://x/v1/models: 503 status code (no body)")
  }

  test("retry-after parsing") {
    assertEquals(RetryAfter.parse(Map("Retry-After-Ms" -> List("250"), "retry-after" -> List("9"))), Some(250.millis))
    assertEquals(RetryAfter.parse(Map("Retry-After" -> List("2"))), Some(2.seconds))
    assertEquals(RetryAfter.parse(Map("retry-after" -> List("-1"))), None)
    assertEquals(RetryAfter.parse(Map("retry-after" -> List("Wed, 21 Oct 2015 07:28:00 GMT"))), Some(Duration.Zero))
    // Longer than a FiniteDuration can hold: unusable, not an exception thrown out of the retry loop.
    assertEquals(RetryAfter.parse(Map("retry-after" -> List("1e12"))), None)
    assertEquals(RetryAfter.parse(Map("retry-after-ms" -> List("1e16"))), None)
  }

  test("backoff and stop rules match the python sdk") {
    def d(a: Int) = RetryPolicy.backoff(a, 500.millis, 5.seconds, 0.25, 0.0)
    assertEquals(d(1), 500.millis)
    assertEquals(d(2), 1.second)
    assertEquals(d(4), 4.seconds)
    assertEquals(d(5), 5.seconds)
    assertEquals(d(40), 5.seconds)
    assertEquals(RetryPolicy.backoff(1, 500.millis, 5.seconds, 0.25, 1.0), 375.millis)
    assertEquals(RetryPolicy.backoff(1, Duration.Zero, 5.seconds, 0.25, 0.5), Duration.Zero)
    val p = RetryPolicy()
    assert(!p.shouldStop(2, Duration.Zero, 1.second))
    assert(p.shouldStop(3, Duration.Zero, 1.second))
    assert(p.shouldStop(1, 29.seconds, 1.second))
    assert(!p.copy(budget = None).shouldStop(1, 99.seconds, 1.second))
    assert(p.httpStatuses.contains(529) && !p.httpStatuses.contains(422))
    intercept[ConfigException](p.copy(backoffJitter = 1.5).validate())
  }

  test("a connection error with no message names its cause instead of saying null") {
    assertEquals(ConnectionException(new java.net.ConnectException()).getMessage, "Connection error: java.net.ConnectException")
    assertEquals(ConnectionException(new java.io.IOException("reset")).getMessage, "Connection error: reset")
  }

  test("a gateway's key is masked in logs as well as the API's") {
    val secret = List("Authorization", "cookie", "cf-aig-authorization", "x-portkey-api-key", "x-gateway-token", "x-client-secret")
    for name <- secret do assert(Constants.isSecret(name), name)
    for name <- List("content-type", "x-typesafe-request-id", "retry-after") do assert(!Constants.isSecret(name), name)
  }

  test("client configuration") {
    val noEnv: String => Option[String] = _ => None
    intercept[ConfigException](TypeSafeClient(ClientConfig(env = noEnv)))
    intercept[ConfigException](TypeSafeClient(ClientConfig(apiKey = Some("k"), timeout = Duration.Zero, env = noEnv)))
    intercept[ConfigException](TypeSafeClient(ClientConfig(apiKey = Some("k"), baseUrl = Some("not a url"), env = noEnv)))
    intercept[ConfigException](TypeSafeClient(ClientConfig(apiKey = Some("k"), baseUrl = Some("/v1"), env = noEnv)))
    intercept[ConfigException](TypeSafeClient(ClientConfig(apiKey = Some("k"), baseUrl = Some("ftp://h"), env = noEnv)))
    assertEquals(TypeSafeClient(ClientConfig(apiKey = Some("  sk-test\n"), env = noEnv)).defaultModel, "jev-latest")
    for bad <- List("", "   ", "sk test", "sk\ttest", "sk-\u007f", "sk-é") do
      val e = intercept[ConfigException](TypeSafeClient(ClientConfig(apiKey = Some(bad), env = noEnv)))
      assert(e.getMessage.contains(if bad.trim.isEmpty then "No API key" else "printable ASCII"), bad)
    val env = Map("TYPESAFE_API_KEY" -> "  envkey ", "TYPESAFE_BASE_URL" -> "http://h:1///", "TYPESAFE_DEFAULT_MODEL" -> "   ")
    val c = TypeSafeClient(ClientConfig(env = env.get))
    assertEquals(c.baseUrl, "http://h:1")
    assertEquals(c.defaultModel, "jev-latest")
    assertEquals(TypeSafeClient(ClientConfig(model = Some("jev-2"), env = env.get)).defaultModel, "jev-2")
  }
