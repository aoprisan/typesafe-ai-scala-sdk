package publicapi

import typesafe.*

/** What a user sees: this suite sits outside the `typesafe` package, so the SDK's own
  * `private[typesafe]` constructors are out of reach here as they are in an application.
  */
class PublicApiSuite extends munit.FunSuite:

  test("instructions that encode to null are left out rather than sent as null") {
    val none: Option[String] = None
    assertEquals(Noul(none).toJson.render, """{"type":"noul"}""")
    assertEquals(Noul(Some("Urgent?")).toJson.render, """{"type":"noul","instructions":"Urgent?"}""")
    assertEquals(Score(none, "Calm", "Angry").toJson.render, """{"type":"score","criteria":["Calm","Angry"]}""")
    assertEquals(Choice.labels(none, "a").toJson.render, """{"type":"choice","criteria":{"a":null}}""")
    assertEquals(Noul().toJson.render, """{"type":"noul"}""")
  }

  test("an answer type is tied to its question: only .named makes a handle") {
    assert(compileErrors("""Asked[ScoreAnswer]("x", Noul("q"))""").nonEmpty)
    assert(compileErrors("""new Asked[ScoreAnswer]("x", Noul("q"))""").nonEmpty)
    val handle: Asked[NoulAnswer] = Noul("q").named("x")
    assertEquals(handle.name, "x")
  }

  test("the case-class constructors and copy are the SDK's own") {
    assert(compileErrors("""Noul(Some(Json.Str("q")), None, None)""").nonEmpty)
    assert(compileErrors("""new Score(None, Vector.empty)""").nonEmpty)
    assert(compileErrors("""Choice("q").copy(instructions = None)""").nonEmpty)
    assert(compileErrors("""ConfigException("x")""").nonEmpty)
  }

  test("pattern matching on questions still works") {
    val q: Question = Score("How frustrated", "Calm", "Angry")
    val levels = q match
      case Score(_, criteria) => criteria.size
      case _                  => 0
    assertEquals(levels, 2)
  }

  test("answer kinds carry their wire names") {
    assertEquals(NoulAnswer(0.5).kind, AnswerKind.Noul)
    assertEquals(AnswerKind.values.map(_.wire).toList, List("noul", "choice", "score"))
    assertEquals(AnswerKind.fromWire("score"), Some(AnswerKind.Score))
    assertEquals(AnswerKind.fromWire("future"), None)
  }
