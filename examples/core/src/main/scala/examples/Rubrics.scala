package examples

import typesafe.*
import typesafe.rubric.*

/** The rubric as a case class: its fields are the questions, and the answers come back into it.
  *
  * With `Questions.of(...)` and `res(handle)` each question is named once already; here the case
  * class is the handle. A field read as the wrong answer type, a field that is not a question or a
  * score without levels does not compile, and the enum's cases are the choice's options, so a
  * `match` on the answer is exhaustive.
  *
  * {{{
  * sbt "examples/runMain examples.rubrics"
  * }}}
  */
case class Triage(
    @noul(
      "The message conveys urgency",
      yes = "A deadline, a threat to leave, or \"ASAP\"",
      no = "Routine, no time pressure"
    )
    isUrgent: NoulAnswer,
    @choice("Which team should handle this")
    department: ChoiceOf[Team],
    @score("How frustrated the customer appears", "Calm", "Frustrated but civil", "Very angry")
    frustration: ScoreAnswer,
    @noul("The customer asks for their money back") @named("wants_refund")
    refund: Double // the probability of "yes", when that is all you need
) derives Rubric

/** The options of the `department` choice, offered as `billing`, `technical` and `sales`. */
enum Team derives RubricChoice:
  @option("Payment or subscription issues") case Billing
  @option("Bugs or integration problems") case Technical
  @option("Pricing or account questions") case Sales

@main def rubrics(): Unit = Demo.run() { config =>
  // What goes on the wire, derived from the case class: look at it before paying for it.
  println(Rubric[Triage].questions.toJson.render)

  val client = TypeSafeClient(config)
  try
    val triage = client.ask[Triage]("The payout failed again, third time this month. I'm done waiting. Refund me.")

    println(f"department  ${triage.department.value} (confidence ${triage.department.confidence}%.2f)")
    println(f"frustration ${triage.frustration.score}%.2f")
    println(s"urgent      ${triage.isUrgent.isYes(0.8)}")
    println(f"refund      ${triage.refund}%.2f")

    // A plain enum: the compiler checks this match covers every option.
    triage.department.value match
      case Team.Billing if triage.frustration.score >= 1.5 => println("→ payments on-call")
      case Team.Billing                                   => println("→ billing queue")
      case Team.Technical                                 => println("→ engineering triage")
      case Team.Sales                                     => println("→ account manager")
  finally client.close()
}
