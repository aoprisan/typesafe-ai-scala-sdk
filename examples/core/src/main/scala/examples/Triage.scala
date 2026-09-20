package examples

import typesafe.*

/** The quick start: name each question once, read its answer back with the right type, and let the
  * confidence decide whether a human needs to look.
  *
  * {{{
  * sbt "examples/runMain examples.triage"
  * }}}
  */
@main def triage(): Unit = Demo.run() { config =>
  val client = TypeSafeClient(config)

  // `named` turns a question into a handle that remembers which answer type it produces, so the
  // read below needs no cast and no string key.
  val department = Choice(
    "Which team should handle this",
    "billing"   -> "Payment or subscription issues",
    "technical" -> "Bugs or integration problems",
    "sales"     -> "Pricing or account questions"
  ).named("department")                                                  // Asked[ChoiceAnswer]

  val frustration = Score(
    "How frustrated the customer appears",
    "Calm, just stating facts",
    "Frustrated but civil",
    "Very angry, strong language"
  ).named("frustration")                                                 // Asked[ScoreAnswer]

  val urgent = Noul("The message conveys urgency or time-sensitivity").named("is_urgent")

  val ticket = "Hi, I've been trying to connect my Stripe account for 3 days and it keeps failing. " +
    "I'm losing sales. Please help ASAP."

  try
    val res = client.systemOne(ticket, Questions.of(department, frustration, urgent))

    val dept = res(department)                                           // ChoiceAnswer
    val route = if dept.confidence >= 0.5 then dept.choice else "human-review"
    println(f"route       → $route (confidence ${dept.confidence}%.2f)")
    println("  runners-up: " + dept.ranked.tail.map((l, p) => f"$l $p%.2f").mkString(", "))

    val mood = res(frustration)                                          // ScoreAnswer
    println(f"frustration → ${mood.score}%.2f of ${mood.legend.size - 1} " +
      s"(${mood.mostLikelyLevel.flatMap(mood.legend.get).map(_.render).getOrElse("?")})")

    println(s"urgent?     → ${res(urgent).isYes(0.8)} (${res(urgent).noul})")
    println(s"model ${res.model}, request ${res.requestId.getOrElse("-")}, " +
      s"${res.meta.attempts} attempt(s), usage ${res.usage}")
  finally client.close()
}
