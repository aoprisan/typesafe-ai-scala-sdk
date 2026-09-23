package typesafe

import scala.concurrent.Await
import scala.concurrent.duration.*

import typesafe.rubric.*

enum Department derives RubricChoice:
  @option("Payment or subscription issues") case Billing
  @option("Bugs or integration problems") case Technical
  @named("presales") case Sales
  case NeedsHuman

case class Triage(
    @noul("The message conveys urgency", yes = "A deadline or ASAP", no = "Routine")
    isUrgent: NoulAnswer,
    @choice("Which team should handle this")
    department: ChoiceOf[Department],
    @score("How frustrated the customer appears", "Calm", "Frustrated but civil", "Very angry")
    frustration: ScoreAnswer,
    @noul("The customer asks for their money back") @named("wants_refund")
    refund: Double,
    @choice("Which team, plainly") team: Department,
    @choice("Which tone", "polite", "rude") tone: String,
    @choice("Which channel", "email", "chat") channel: ChoiceAnswer,
    @score("How long it took", "Minutes", "Days") delay: Double
) derives Rubric

class RubricSuite extends munit.FunSuite:
  private val api = MockApi()
  import api.Reply

  override def afterAll(): Unit = api.stop()

  private def client =
    TypeSafeClient(ClientConfig(apiKey = Some("sk-test"), baseUrl = Some(api.url), retry = RetryPolicy.none, env = _ => None))

  private val choiceOf = (label: String) =>
    s"""{"type":"choice","choice":"$label","probabilities":{"billing":0.1,"technical":0.8,"presales":0.05,"needs_human":0.05},"confidence":0.7}"""
  private val score =
    """{"type":"score","score":1.4,"legend":{"0":"Calm","1":"Frustrated but civil","2":"Very angry"},"probabilities":{"0":0.1,"1":0.4,"2":0.5},"confidence":0.5}"""

  private def body(department: String = choiceOf("technical"), extra: String = "") =
    s"""{"model":"jev-latest","answers":{
       |"is_urgent":{"type":"noul","noul":0.9},
       |"department":$department,
       |"frustration":$score,
       |"wants_refund":{"type":"noul","noul":0.25},
       |"team":${choiceOf("presales")},
       |"tone":{"type":"choice","choice":"rude","probabilities":{"polite":0.3,"rude":0.7},"confidence":0.4},
       |"channel":{"type":"choice","choice":"chat","probabilities":{"email":0.2,"chat":0.8},"confidence":0.6},
       |"delay":{"type":"score","score":0.3,"legend":{"0":"Minutes","1":"Days"},"probabilities":{"0":0.7,"1":0.3},"confidence":0.4}
       |$extra},"usage":{}}""".stripMargin

  test("the questions come from the fields, named in snake_case, in field order") {
    val qs = Rubric[Triage].questions
    assertEquals(qs.entries.keys.toList, List("is_urgent", "department", "frustration", "wants_refund", "team", "tone", "channel", "delay"))
    assertEquals(
      qs.entries("is_urgent"),
      Noul("The message conveys urgency").describeTrue("A deadline or ASAP").describeFalse("Routine")
    )
    assertEquals(
      qs.entries("department"),
      Choice(
        "Which team should handle this",
        "billing" -> "Payment or subscription issues",
        "technical" -> "Bugs or integration problems"
      ).label("presales").label("needs_human")
    )
    assertEquals(qs.entries("frustration"), Score("How frustrated the customer appears", "Calm", "Frustrated but civil", "Very angry"))
    assertEquals(qs.entries("tone"), Choice.labels("Which tone", "polite", "rude"))
    assertEquals(qs.entries("delay"), Score("How long it took", "Minutes", "Days"))
  }

  test("client.ask sends the questions and decodes the answers into the case class") {
    api.respond(_ => Reply(200, body()))
    val t = client.ask[Triage]("The payout failed again. Refund me.")
    assertEquals(t.isUrgent.noul, 0.9)
    assertEquals(t.department.value, Department.Technical)
    assertEquals(t.department.confidence, 0.7)
    assertEquals(t.department.probability(Department.NeedsHuman), Some(0.05))
    assertEquals(t.frustration.mostLikelyLevel, Some(2))
    assertEquals(t.refund, 0.25)
    assertEquals(t.team, Department.Sales)
    assertEquals(t.tone, "rude")
    assertEquals(t.channel.choice, "chat")
    assertEquals(t.delay, 0.3)
    val sent = Json.unsafeParse(api.requests.head.body)
    assertEquals(sent.get("questions"), Some(Rubric[Triage].questions.toJson))

    api.respond(_ => Reply(200, body()))
    assertEquals(Await.result(client.askFuture[Triage]("again", CallOptions(model = Some("jev-2"))), 5.seconds).team, Department.Sales)
    assertEquals(Json.unsafeParse(api.requests.head.body).get("model"), Some(Json.Str("jev-2")))
    api.respond(_ => Reply(200, body()))
    assertEquals(client.askAsync[Triage]("again").get().refund, 0.25)
  }

  test("a response the case class cannot hold names the answer") {
    def failure(b: String) =
      api.respond(_ => Reply(200, b))
      intercept[ResponseValidationException](client.ask[Triage]("x"))

    val unknown = failure(body(department = choiceOf("legal")))
    assertEquals(unknown.fieldPath, "answers.department.choice")
    assert(unknown.detail.contains("\"legal\""), unknown.detail)
    assert(unknown.detail.contains("\"needs_human\""), unknown.detail)

    val wrongType = failure(body(department = """{"type":"noul","noul":0.5}"""))
    assertEquals(wrongType.fieldPath, "answers.department")
    assertEquals(wrongType.detail, "expected a choice answer, got a noul")

    val strange = failure(body(department = """{"type":"ranking","order":[]}"""))
    assertEquals(strange.detail, "the answer is of a type this SDK does not know; expected a choice")

    val missing = failure(body().replace("\"wants_refund\"", "\"something_else\""))
    assertEquals(missing.fieldPath, "answers.wants_refund")
    assertEquals(missing.detail, "no answer; expected a noul")
  }

  test("RubricChoice offers the cases as snake_case labels and reads them back") {
    val c = RubricChoice[Department]
    assertEquals(c.options.map(_._1), Vector("billing", "technical", "presales", "needs_human"))
    assertEquals(c.fromLabel("needs_human"), Some(Department.NeedsHuman))
    assertEquals(c.label(Department.Sales), "presales")
    assert(c.parse("nope").isLeft)
    assertEquals(RubricMacros.snakeCase("HTTPError"), "http_error")
    assertEquals(RubricMacros.snakeCase("isUrgent2x"), "is_urgent2x")
  }

  test("mistakes do not compile") {
    assert(compileErrors("""
      case class R(@noul("Urgent?") isUrgent: ScoreAnswer) derives Rubric
    """).contains("a @noul field is a NoulAnswer or a Double"))
    assert(compileErrors("""
      case class R(@choice("Which team") team: Int) derives Rubric
    """).contains("a @choice field is an enum deriving RubricChoice"))
    assert(compileErrors("""
      case class R(@choice("Which team") team: String) derives Rubric
    """).contains("has no options of its own"))
    assert(compileErrors("""
      case class R(@choice("Which team", "a", "b") team: Department) derives Rubric
    """).contains("brings its own options"))
    assert(compileErrors("""
      case class R(@score("How angry") anger: ScoreAnswer) derives Rubric
    """).contains("needs its levels"))
    assert(compileErrors("""
      case class R(@noul("Urgent?") isUrgent: NoulAnswer, note: String) derives Rubric
    """).contains("R.note is not a question"))
    assert(compileErrors("""
      case class R(@noul("Urgent?") isUrgent: NoulAnswer, @noul("Really?") @named("is_urgent") very: NoulAnswer) derives Rubric
    """).contains("both ask under the name \"is_urgent\""))
    assert(compileErrors("""
      enum Team derives RubricChoice:
        case Billing
        case Other(note: String)
    """).nonEmpty)
    assertEquals(compileErrors("""
      case class R(@noul("Urgent?", yes = "A deadline") isUrgent: NoulAnswer, @score("How angry", "Calm", "Angry") anger: Double) derives Rubric
    """), "")
  }
