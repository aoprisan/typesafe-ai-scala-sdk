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
    api.respond(_ => Reply(200, body()))
    assertEquals(client.askEither[Triage]("again").map(_.tone), Right("rude"))
    api.respond(_ => Reply(503))
    assert(client.askEither[Triage]("again").left.exists(_.isInstanceOf[ApiException]))
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
    // The API's limits: a score has 2 to 10 levels, a choice up to 255 options.
    assert(compileErrors("""
      case class R(@score("How angry", "Furious") anger: ScoreAnswer) derives Rubric
    """).contains("a @score takes 2 to 10 levels, this one has 1"))
    assert(compileErrors("""
      case class R(@score("How bad", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10") bad: ScoreAnswer) derives Rubric
    """).contains("a @score takes 2 to 10 levels, this one has 11"))
    assert(compileErrors("""
      case class R(@choice("Which",
        "o0", "o1", "o2", "o3", "o4", "o5", "o6", "o7", "o8", "o9", "o10", "o11", "o12", "o13", "o14", "o15",
        "o16", "o17", "o18", "o19", "o20", "o21", "o22", "o23", "o24", "o25", "o26", "o27", "o28", "o29",
        "o30", "o31", "o32", "o33", "o34", "o35", "o36", "o37", "o38", "o39", "o40", "o41", "o42", "o43",
        "o44", "o45", "o46", "o47", "o48", "o49", "o50", "o51", "o52", "o53", "o54", "o55", "o56", "o57",
        "o58", "o59", "o60", "o61", "o62", "o63", "o64", "o65", "o66", "o67", "o68", "o69", "o70", "o71",
        "o72", "o73", "o74", "o75", "o76", "o77", "o78", "o79", "o80", "o81", "o82", "o83", "o84", "o85",
        "o86", "o87", "o88", "o89", "o90", "o91", "o92", "o93", "o94", "o95", "o96", "o97", "o98", "o99",
        "o100", "o101", "o102", "o103", "o104", "o105", "o106", "o107", "o108", "o109", "o110", "o111",
        "o112", "o113", "o114", "o115", "o116", "o117", "o118", "o119", "o120", "o121", "o122", "o123",
        "o124", "o125", "o126", "o127", "o128", "o129", "o130", "o131", "o132", "o133", "o134", "o135",
        "o136", "o137", "o138", "o139", "o140", "o141", "o142", "o143", "o144", "o145", "o146", "o147",
        "o148", "o149", "o150", "o151", "o152", "o153", "o154", "o155", "o156", "o157", "o158", "o159",
        "o160", "o161", "o162", "o163", "o164", "o165", "o166", "o167", "o168", "o169", "o170", "o171",
        "o172", "o173", "o174", "o175", "o176", "o177", "o178", "o179", "o180", "o181", "o182", "o183",
        "o184", "o185", "o186", "o187", "o188", "o189", "o190", "o191", "o192", "o193", "o194", "o195",
        "o196", "o197", "o198", "o199", "o200", "o201", "o202", "o203", "o204", "o205", "o206", "o207",
        "o208", "o209", "o210", "o211", "o212", "o213", "o214", "o215", "o216", "o217", "o218", "o219",
        "o220", "o221", "o222", "o223", "o224", "o225", "o226", "o227", "o228", "o229", "o230", "o231",
        "o232", "o233", "o234", "o235", "o236", "o237", "o238", "o239", "o240", "o241", "o242", "o243",
        "o244", "o245", "o246", "o247", "o248", "o249", "o250", "o251", "o252", "o253", "o254", "o255") pick: String) derives Rubric
    """).contains("a @choice takes at most 255 options, this one has 256"))
    assert(compileErrors("""
      enum Many derives RubricChoice:
        case C0, C1, C2, C3, C4, C5, C6, C7, C8, C9, C10, C11, C12, C13, C14, C15, C16, C17, C18, C19, C20, C21,
             C22, C23, C24, C25, C26, C27, C28, C29, C30, C31, C32, C33, C34, C35, C36, C37, C38, C39, C40, C41,
             C42, C43, C44, C45, C46, C47, C48, C49, C50, C51, C52, C53, C54, C55, C56, C57, C58, C59, C60, C61,
             C62, C63, C64, C65, C66, C67, C68, C69, C70, C71, C72, C73, C74, C75, C76, C77, C78, C79, C80, C81,
             C82, C83, C84, C85, C86, C87, C88, C89, C90, C91, C92, C93, C94, C95, C96, C97, C98, C99, C100, C101,
             C102, C103, C104, C105, C106, C107, C108, C109, C110, C111, C112, C113, C114, C115, C116, C117, C118,
             C119, C120, C121, C122, C123, C124, C125, C126, C127, C128, C129, C130, C131, C132, C133, C134, C135,
             C136, C137, C138, C139, C140, C141, C142, C143, C144, C145, C146, C147, C148, C149, C150, C151, C152,
             C153, C154, C155, C156, C157, C158, C159, C160, C161, C162, C163, C164, C165, C166, C167, C168, C169,
             C170, C171, C172, C173, C174, C175, C176, C177, C178, C179, C180, C181, C182, C183, C184, C185, C186,
             C187, C188, C189, C190, C191, C192, C193, C194, C195, C196, C197, C198, C199, C200, C201, C202, C203,
             C204, C205, C206, C207, C208, C209, C210, C211, C212, C213, C214, C215, C216, C217, C218, C219, C220,
             C221, C222, C223, C224, C225, C226, C227, C228, C229, C230, C231, C232, C233, C234, C235, C236, C237,
             C238, C239, C240, C241, C242, C243, C244, C245, C246, C247, C248, C249, C250, C251, C252, C253, C254,
             C255
    """).contains("a choice takes at most 255 options, this enum has 256"))
    assertEquals(compileErrors("""
      case class R(@noul("Urgent?", yes = "A deadline") isUrgent: NoulAnswer, @score("How angry", "Calm", "Angry") anger: Double,
        @score("How bad", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9") bad: ScoreAnswer) derives Rubric
    """), "")
  }
